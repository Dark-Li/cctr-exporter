package top.kagg886.cctr.desktop.util

import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.navigation.PdfStringDestination
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.layout.LayoutArea
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.layout.LayoutResult
import com.itextpdf.layout.renderer.AbstractRenderer
import com.itextpdf.layout.renderer.DivRenderer
import com.itextpdf.layout.renderer.IRenderer
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.driver.CapturedPdfAsset
import java.io.EOFException
import java.io.File
import java.util.zip.ZipException

internal data class QuestionPdfResult(val exportedCount: Int, val skippedIds: List<String>)

internal suspend fun writeSelectableQuestionPdf(
    file: File,
    details: PdfExportDetails,
    questions: List<Question>,
    renderComplex: suspend (String) -> CapturedPdfAsset = { error("此题含需要 Edge 渲染的局部内容") },
): QuestionPdfResult {
    require(questions.isNotEmpty()) { "没有可导出的题目" }
    // The old PNG path kept the later value when the same question ID appeared twice in a group.
    val ordered = questions.associateBy { it.id }.values.sortedWith(compareBy(naturalQuestionOrder) { it.id })
    val (exportable, skipped) = ordered.partition(Question::hasDecodableSubject)
    if (exportable.isNotEmpty()) {
        writeAtomicPdf(file) { pdf ->
            SelectableQuestionLayout(pdf, details, renderComplex).write(exportable)
        }
    }
    return QuestionPdfResult(exportable.size, skipped.map(Question::id))
}

private fun Question.hasDecodableSubject(): Boolean = try {
    subjectHtml.isNotBlank()
} catch (_: IllegalArgumentException) {
    false // Invalid Base64.
} catch (_: EOFException) {
    false // Truncated gzip header or body.
} catch (_: ZipException) {
    false // Invalid gzip header, compressed data or checksum.
}

private class SelectableQuestionLayout(
    private val pdf: PdfDocument,
    private val details: PdfExportDetails,
    renderComplex: suspend (String) -> CapturedPdfAsset,
) {
    private val pageSize = PageSize.A4
    private val side = 42f
    private val top = 70f
    private val bottom = 53f
    private val bodyWidth = pageSize.width - 2 * side
    private val bodyHeight = pageSize.height - top - bottom
    private val ink = DeviceRgb(35, 48, 61)
    private val muted = DeviceRgb(83, 99, 111)
    private val accent = DeviceRgb(43, 86, 109)
    private val rule = DeviceRgb(219, 225, 230)
    private val font = createQuestionPdfFont(cached = false)
    private val html = PdfHtmlContent(pdf, font, bodyWidth, renderComplex)
    private val continuationPages = mutableMapOf<Int, Int>()

    suspend fun write(questions: List<Question>) {
        pdf.documentInfo.setTitle(listOf(details.title, details.chapter, details.questionType).filter(String::isNotBlank).joinToString(" / "))
        pdf.documentInfo.setCreator("CCTRCloudExportDesktop")
        pdf.catalog.setLang(PdfString("zh-CN"))
        // Keep pages writable until the final page count is known.
        Document(pdf, pageSize, false).use { document ->
            document.setMargins(top, side, bottom, side)
            document.setFont(font).setFontColor(ink).setFontSize(11f)
            document.add(heading("题库导出", 9f, accent).setMarginBottom(5f))
            document.add(heading(details.title.ifBlank { "题库" }, 20f, ink).setMarginBottom(5f))
            document.add(heading(listOf(details.chapter, details.questionType, "共 ${questions.size} 题").filter(String::isNotBlank).joinToString(" / "), 10f, muted).setMarginBottom(15f))
            questions.forEachIndexed { index, question ->
                try {
                    val block = questionBlock(index + 1, question)
                    val measure = block.createRendererSubTree().setParent(document.renderer)
                        .layout(LayoutContext(LayoutArea(1, Rectangle(bodyWidth, bodyHeight))))
                    if (measure.status == LayoutResult.FULL) block.setKeepTogether(true)
                    block.setNextRenderer(QuestionContinuationRenderer(block, index + 1, continuationPages))
                    document.add(block)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw IllegalStateException("题目 ID ${question.id} 无法排版：${error.message ?: error.javaClass.simpleName}", error)
                }
            }
            addPageFurniture()
        }
    }

    private suspend fun questionBlock(number: Int, question: Question): Div {
        val label = "第 $number 题 · ID ${question.id}"
        val anchor = "question-$number"
        pdf.getOutlines(false).addOutline(label).addDestination(PdfStringDestination(anchor))
        val block = Div().setBorderTop(SolidBorder(rule, 0.5f)).setPaddingTop(7f).setMarginBottom(10f)
        block.add(heading("$label  ${question.questionType}", 9.5f, accent).setDestination(anchor).setMarginBottom(6f))
        html.addHtml(question.subjectHtml, block::add)

        if (question.questionType.trim() == "填空题") {
            addFillBlankAnswer(question, block)
            return block
        }

        val choices = if (question.hasOptions) question.options else emptyList()
        if (choices.isNotEmpty()) {
            val choiceBodies = choices.map { Jsoup.parseBodyFragment(it.html).body() }
            val visibleLabels = choiceBodies.map { optionPrefix.find(it.text())?.groupValues?.get(1)?.uppercase() }
            val sourceLabels = choices.map { it.sourceKey?.trim()?.takeIf(optionKey::matches)?.uppercase() }
            val reservedLabels = visibleLabels.zip(sourceLabels) { visible, key -> visible ?: key }.filterNotNull().toMutableSet()
            val correct = mutableListOf<String>()
            val judgment = question.questionType.contains("判断") || question.questionType.contains("是非")
            choices.forEachIndexed { index, option ->
                val body = choiceBodies[index]
                val semanticAnswer = body.text().replaceFirst(optionPrefix, "").trim().trimEnd('。', '！', '.', '!')
                    .takeIf { judgment && it in judgmentLabels }
                val optionLabel = visibleLabels[index] ?: sourceLabels[index] ?: nextOptionLabel(index, reservedLabels)
                val prefix = if (visibleLabels[index] == null && semanticAnswer == null) "$optionLabel. " else ""
                val optionIndent = 8f
                val optionBlock = Div().setMarginLeft(optionIndent)
                html.addNodes(body.childNodes(), optionBlock::add, prefix, bodyWidth - optionIndent)
                block.add(optionBlock)
                if (option.isTrue) correct.add(semanticAnswer ?: optionLabel)
            }
            block.add(answerLine(correct.joinToString("、").ifBlank { "平台未提供" }))
        } else {
            val formatted = question.formattedAnswerHtml?.takeIf { it.isNotBlank() }
            if (formatted != null) {
                block.add(answerHeading())
                html.addHtml(formatted, block::add)
            } else {
                block.add(answerLine(question.plainAnswer?.takeIf { it.isNotBlank() } ?: "平台未提供"))
            }
        }
        return block
    }

    private suspend fun addFillBlankAnswer(question: Question, block: Div) {
        val formatted = question.formattedAnswerHtml?.let(::visiblePdfHtmlNodes)
        if (formatted != null) {
            block.add(answerHeading())
            html.addNodes(formatted, block::add)
            return
        }

        val plain = question.plainAnswer?.takeIf(String::hasVisiblePdfText)
        if (plain != null) {
            block.add(answerLine(plain))
            return
        }

        val fragments = question.fillBlankOptionHtml().mapNotNull(::visiblePdfHtmlNodes).iterator()
        if (fragments.hasNext()) {
            block.add(answerHeading())
            while (fragments.hasNext()) html.addNodes(fragments.next(), block::add)
        } else {
            block.add(answerLine("平台未提供"))
        }
    }

    private fun answerHeading(): Paragraph = heading("参考答案", 10f, accent).setMarginTop(5f).setMarginBottom(3f)

    private fun answerLine(value: String): Paragraph = Paragraph(html.pdfText("参考答案：$value"))
        .setFontColor(accent).setFontSize(10.5f).setMarginTop(4f).setMarginBottom(0f)

    private fun heading(value: String, size: Float, color: DeviceRgb): Paragraph = Paragraph(html.pdfText(value))
        .setFontColor(color).setFontSize(size).setMargin(0f).setKeepWithNext(true)

    private fun addPageFurniture() {
        val total = pdf.numberOfPages
        val header = listOf(details.chapter, details.questionType).filter(String::isNotBlank).joinToString(" / ")
        for (number in 1..total) {
            val page = pdf.getPage(number)
            val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdf)
            val left = fit(details.title, bodyWidth * 0.54f)
            val right = fit(header, bodyWidth * 0.42f)
            val pageLabel = "$number / $total"
            canvasText(canvas, left, side, pageSize.height - 32f)
            canvasText(canvas, right, pageSize.width - side - font.getWidth(right, 8f), pageSize.height - 32f)
            continuationPages[number]?.let { canvasText(canvas, "第 $it 题 · 续", side, pageSize.height - 55f) }
            canvasText(canvas, "CCTR · 题库", side, 24f)
            canvasText(canvas, pageLabel, pageSize.width - side - font.getWidth(pageLabel, 8f), 24f)
            canvas.setStrokeColor(rule).setLineWidth(0.5f)
            for (y in listOf(pageSize.height - 42f, 38f)) {
                canvas.moveTo(side.toDouble(), y.toDouble()).lineTo((pageSize.width - side).toDouble(), y.toDouble()).stroke()
            }
        }
    }

    private fun fit(value: String, width: Float): String {
        val plain = value.replace(Regex("\\s+"), " ")
        if (font.getWidth(plain, 8f) <= width) return plain
        return buildString {
            for (point in plain.codePoints().toArray()) {
                val character = String(Character.toChars(point))
                if (font.getWidth(toString() + character + "…", 8f) > width) break
                append(character)
            }
            append("…")
        }
    }

    private fun canvasText(canvas: PdfCanvas, value: String, x: Float, y: Float) {
        canvas.beginText().setFontAndSize(font, 8f).setFillColor(muted)
            .moveText(x.toDouble(), y.toDouble()).showText(html.pdfText(value).text).endText()
    }
}

private class QuestionContinuationRenderer(
    private val block: Div,
    private val questionNumber: Int,
    private val continuationPages: MutableMap<Int, Int>,
    private var continued: Boolean = false,
) : DivRenderer(block) {
    override fun getNextRenderer(): IRenderer = QuestionContinuationRenderer(block, questionNumber, continuationPages, continued)

    override fun createOverflowRenderer(layoutResult: Int): AbstractRenderer =
        (super.createOverflowRenderer(layoutResult) as QuestionContinuationRenderer).apply {
            continued = continued || layoutResult == LayoutResult.PARTIAL
        }

    override fun layout(layoutContext: LayoutContext): LayoutResult = super.layout(layoutContext).also { result ->
        if (continued && result.status != LayoutResult.NOTHING) {
            continuationPages.putIfAbsent(result.occupiedArea.pageNumber, questionNumber)
        }
    }
}

private val optionPrefix = Regex("^\\s*([A-Za-z]|[A-Z]{2}(?=[.．、:：)）]\\s))\\s*[.．、:：)）]\\s*")
private val optionKey = Regex("[A-Za-z]|[A-Z]{2}")
private val judgmentLabels = setOf("正确", "错误", "对", "错", "是", "否", "√", "×", "True", "False", "true", "false")

private fun nextOptionLabel(index: Int, reserved: MutableSet<String>): String {
    var ordinal = index + 1
    while (true) {
        var number = ordinal++
        val label = buildString {
            while (number > 0) {
                number--
                append('A' + number % 26)
                number /= 26
            }
        }.reversed()
        if (reserved.add(label)) return label
    }
}
