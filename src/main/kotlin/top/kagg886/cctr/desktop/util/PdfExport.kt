package top.kagg886.cctr.desktop.util

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.navigation.PdfExplicitDestination
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class PdfExportDetails(val title: String, val chapter: String = "", val questionType: String = "")

/** Write to a sibling temporary file so a failed conversion never replaces a previous export. */
fun List<File>.convertToPDF(file: File, details: PdfExportDetails): Boolean {
    return try {
        require(isNotEmpty()) { "没有可导出的题目" }
        writeAtomicPdf(file) { pdf ->
            QuestionPdfLayout(pdf, details).write(sortedWith(compareBy(naturalQuestionOrder) { it.nameWithoutExtension }))
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal inline fun writeAtomicPdf(file: File, write: (PdfDocument) -> Unit) {
    writeAtomicFile(file) { temporary -> PdfDocument(PdfWriter(temporary)).use(write) }
}

internal inline fun writeAtomicFile(file: File, write: (File) -> Unit) {
    val target = file.absoluteFile.toPath()
    Files.createDirectories(target.parent)
    val temporary: Path = Files.createTempFile(target.parent, ".cctr-pdf-", ".tmp")
    try {
        write(temporary.toFile())
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private val fontBytes by lazy {
    requireNotNull(PdfExportDetails::class.java.getResourceAsStream("/fonts/SourceHanSansCN-Regular.otf")) {
        "缺少 PDF 中文字体资源"
    }.use { it.readBytes() }
}

private val questionIdParts = Regex("[0-9]+|[^0-9]+")
internal val naturalQuestionOrder = Comparator<String> { left, right ->
    val a = questionIdParts.findAll(left).map { it.value }.toList()
    val b = questionIdParts.findAll(right).map { it.value }.toList()
    var result = 0
    for (index in 0 until min(a.size, b.size)) {
        val x = a[index]
        val y = b[index]
        result = if (x.first().isDigit() && y.first().isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }
            val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else x.compareTo(y, ignoreCase = true)
        if (result != 0) break
    }
    if (result == 0) result = a.size.compareTo(b.size)
    if (result == 0) result = left.compareTo(right)
    result
}

internal fun createQuestionPdfFont(cached: Boolean = true): PdfFont = PdfFontFactory.createFont(
    fontBytes, PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED, cached
).apply { setSubset(true) }

private class QuestionPdfLayout(private val pdf: PdfDocument, private val details: PdfExportDetails) {
    private val width = PageSize.A4.width
    private val height = PageSize.A4.height
    private val margin = 42f
    private val contentWidth = width - 2 * margin
    private val bottom = 54f
    private val pageTop = height - 76f
    private val ink = DeviceRgb(35, 48, 61)
    private val muted = DeviceRgb(104, 116, 127)
    private val accent = DeviceRgb(43, 86, 109)
    private val line = DeviceRgb(219, 225, 230)
    private val font = createQuestionPdfFont()
    private lateinit var page: PdfPage
    private lateinit var canvas: PdfCanvas
    private var cursor = pageTop

    fun write(files: List<File>) {
        pdf.documentInfo.setTitle(listOf(details.title, details.chapter, details.questionType).filter { it.isNotBlank() }.joinToString(" / "))
        pdf.documentInfo.setCreator("CCTRCloudExportDesktop")
        pdf.catalog.setLang(com.itextpdf.kernel.pdf.PdfString("zh-CN"))
        newPage()
        text("题库导出", margin, cursor, 9f, accent)
        cursor -= 28f
        wrapped(details.title.ifBlank { "题库" }, 19f).forEach {
            text(it, margin, cursor, 19f, ink); cursor -= 26f
        }
        if (details.chapter.isNotBlank()) wrapped(details.chapter, 11f).forEach {
            text(it, margin, cursor, 11f, muted); cursor -= 17f
        }
        val summary = listOf(details.questionType, "共 ${files.size} 题", "含参考答案").filter { it.isNotBlank() }.joinToString("   /   ")
        wrapped(summary, 10f).forEach { text(it, margin, cursor, 10f, muted); cursor -= 16f }
        cursor -= 7f
        rule(cursor)
        cursor -= 22f

        files.forEachIndexed { index, file -> addQuestion(index + 1, file) }
        for (number in 1..pdf.numberOfPages) {
            val footer = PdfCanvas(pdf.getPage(number))
            footer.setStrokeColor(line).setLineWidth(0.5f).moveTo(margin.toDouble(), 39.0).lineTo((width - margin).toDouble(), 39.0).stroke()
            text("CCTR · 题库", margin, 24f, 8f, muted, footer)
            val label = "$number / ${pdf.numberOfPages}"
            text(label, width - margin - font.getWidth(label, 8f), 24f, 8f, muted, footer)
        }
    }

    private fun newPage() {
        page = pdf.addNewPage(PageSize.A4)
        canvas = PdfCanvas(page)
        cursor = pageTop
        text(fit(details.title, contentWidth * 0.55f, 8f), margin, height - 34f, 8f, muted)
        val context = listOf(details.chapter, details.questionType).filter { it.isNotBlank() }.joinToString(" / ")
        val right = fit(context, contentWidth * 0.42f, 8f)
        text(right, width - margin - font.getWidth(right, 8f), height - 34f, 8f, muted)
        rule(height - 44f)
    }

    private fun addQuestion(number: Int, file: File) {
        val source = requireNotNull(ImageIO.read(file)) { "无法读取题图 ${file.name}" }
        val blank = BooleanArray(source.height) { isBlankRow(source, it) }
        val first = blank.indexOfFirst { !it }.let { if (it < 0) 0 else max(0, it - 6) }
        val last = blank.indexOfLast { !it }.let { if (it < 0) source.height else min(source.height, it + 7) }
        val scale = min(1f, contentWidth / source.width)
        val imageWidth = source.width * scale
        val imageHeight = source.height * scale
        val questionHeight = (last - first) * scale + 26f
        val availableHeight = cursor - bottom
        val fitsOnOnePage = questionHeight <= pageTop - bottom
        if ((fitsOnOnePage && questionHeight > availableHeight) || (!fitsOnOnePage && availableHeight < 180f)) {
            newPage()
        }
        val label = "第 $number 题"
        text(label, margin, cursor, 10f, accent)
        text(fit("ID ${file.nameWithoutExtension}", contentWidth - 90f, 8f), margin + 82f, cursor, 8f, muted)
        pdf.getOutlines(false).addOutline("$label · ID ${file.nameWithoutExtension}")
            .addDestination(PdfExplicitDestination.createXYZ(page, margin, cursor + 12f, 0f))
        cursor -= 13f
        val image = PdfImageXObject(ImageDataFactory.create(file.readBytes()))
        var start = first
        while (start < last) {
            val availableRows = floor((cursor - bottom) / scale).toInt()
            if (availableRows <= 0) { continuation(number); continue }
            val limit = min(last, start + availableRows)
            var end = limit
            if (limit < last) {
                // Cut within an exact white/transparent row near the page end; otherwise keep every row.
                val earliest = max(start + 1, limit - min(80, availableRows / 5))
                for (row in limit - 1 downTo earliest) {
                    if (blank[row]) { end = row + 1; break }
                }
            }
            val sliceHeight = (end - start) * scale
            val sliceBottom = cursor - sliceHeight
            canvas.saveState().rectangle(margin.toDouble(), sliceBottom.toDouble(), imageWidth.toDouble(), sliceHeight.toDouble()).clip().endPath()
                .addXObjectWithTransformationMatrix(image, imageWidth, 0f, 0f, imageHeight, margin, cursor + start * scale - imageHeight)
                .restoreState()
            cursor = sliceBottom
            start = end
            if (start < last) continuation(number)
        }
        cursor -= 13f
        rule(cursor)
        cursor -= 20f
    }

    private fun continuation(number: Int) {
        newPage()
        text("第 $number 题 · 续", margin, cursor, 9f, muted)
        cursor -= 14f
    }

    private fun isBlankRow(image: BufferedImage, y: Int): Boolean {
        for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            if ((pixel ushr 24) != 0 && (pixel and 0xFFFFFF) != 0xFFFFFF) return false
        }
        return true
    }

    private fun rule(y: Float) {
        canvas.setStrokeColor(line).setLineWidth(0.5f).moveTo(margin.toDouble(), y.toDouble()).lineTo((width - margin).toDouble(), y.toDouble()).stroke()
    }

    private fun clean(value: String): String = buildString {
        value.replace(Regex("\\s+"), " ").codePoints().forEach { appendCodePoint(if (font.containsGlyph(it)) it else '?'.code) }
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: DeviceRgb, target: PdfCanvas = canvas) {
        target.beginText().setFontAndSize(font, size).setFillColor(color).moveText(x.toDouble(), y.toDouble()).showText(clean(value)).endText()
    }

    private fun fit(value: String, maxWidth: Float, size: Float): String {
        val text = clean(value)
        if (font.getWidth(text, size) <= maxWidth) return text
        var result = ""
        for (character in text) {
            if (font.getWidth("$result$character…", size) > maxWidth) break
            result += character
        }
        return "$result…"
    }

    private fun wrapped(value: String, size: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (character in clean(value)) {
            if (font.getWidth(current + character, size) > contentWidth && current.isNotEmpty()) {
                lines.add(current); current = ""
            }
            current += character
        }
        if (current.isNotEmpty()) lines.add(current)
        // Bound the title block so unusually long course metadata cannot consume the question area.
        return if (lines.size > 3) lines.take(2) + fit(lines.drop(2).joinToString(""), contentWidth, size) else lines
    }
}
