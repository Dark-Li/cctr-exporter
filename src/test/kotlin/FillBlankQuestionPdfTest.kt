import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class FillBlankQuestionPdfTest {
    @Test
    fun prefersVisibleHtmlThenPlainAnswerOverOptionPayload() = runBlocking {
        val fromOptions = JsonArray(listOf(option("不应出现的备选内容", 0)))
        val sample = export("answer-priority", listOf(
            question("1", "原题空格 ______ 保留。", fromOptions, "<p>平台格式化答文</p>", "不应出现的原文"),
            question("2", "另一个空格 ______。", fromOptions, "<div><p>&nbsp;&#8203;<br></p><span hidden>隐藏文本</span></div>", "平台原文答文"),
        ))
        PdfDocument(PdfReader(sample)).use { pdf ->
            val text = text(pdf)
            assertTrue(text.contains("原题空格 ______ 保留。"), text)
            assertTrue(text.contains("平台格式化答文"), text)
            assertTrue(text.contains("参考答案：平台原文答文"), text)
            assertFalse(text.contains("不应出现"), text)
            assertFalse(text.contains("隐藏文本"), text)
            assertFalse(text.contains("平台未提供"), text)
            assertFalse(Regex("(?m)^[A-Z]\\.").containsMatchIn(text), text)
        }
    }

    @Test
    fun listsAnswerEntriesInPayloadOrderWithoutChoiceOrBlankNumbers() = runBlocking {
        val objectOptions = buildJsonObject {
            put("2", option("先出现的答案", 0))
            put("9", option("<p>&nbsp;<br></p>", 1))
            put("1", option("后出现的答案")) // Fill-in payload can omit the choice correctness flag.
        }
        val sample = export("fill-blank", listOf(
            question("3", "原题保留两个空格 ______、______。", objectOptions, "<p>&nbsp;</p>", "  "),
            question("4", "单条答案对应的原题 ______。", JsonArray(listOf(option("单条答案正文", 1)))),
            question("5", "没有答案的原题 ______。", JsonArray(listOf(option("<p>&nbsp;&#8203;</p>", 0)))),
            question("6", "空数组的原题 ______。", JsonArray(emptyList())),
            question("7", "空对象的原题 ______。", buildJsonObject {}),
        ))
        PdfDocument(PdfReader(sample)).use { pdf ->
            val text = text(pdf)
            assertTrue(text.contains("原题保留两个空格 ______、______。"), text)
            val answerArea = text.substringAfter("原题保留两个空格 ______、______。").substringBefore("第 2 题")
            assertTrue(answerArea.indexOf("参考答案") in 0 until answerArea.indexOf("先出现的答案"), answerArea)
            assertTrue(answerArea.indexOf("先出现的答案") in 0 until answerArea.indexOf("后出现的答案"), answerArea)
            assertTrue(text.contains("单条答案正文"), text)
            assertEquals(3, Regex("平台未提供").findAll(text).count(), text)
            assertFalse(Regex("(?m)^[A-Z]\\.|第\\s*\\d+\\s*空").containsMatchIn(text), text)
        }
    }

    @Test
    fun preservesFormulaOnlyHtmlAndOptionAnswers() = runBlocking {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="18"><path d="M2 9h36m-17-7v14" stroke="black"/></svg>"""
        val encoded = Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        val formula = "<p><img src='data:image/svg+xml;base64,$encoded' width='40'></p>"
        val sample = export("formula-answer", listOf(
            question("1", "公式答文 ______。", JsonNull, formula, "不应出现的原文"),
            question("2", "公式条目 ______。", JsonArray(listOf(option(formula)))),
        ))
        PdfDocument(PdfReader(sample)).use { pdf ->
            val text = text(pdf)
            assertEquals(2, Regex("参考答案").findAll(text).count(), text)
            assertFalse(text.contains("不应出现"), text)
            assertFalse(text.contains("平台未提供"), text)
            val images = (1..pdf.numberOfPages).sumOf { pdf.getPage(it).resources.getResource(PdfName.XObject)?.keySet()?.size ?: 0 }
            assertEquals(2, images)
        }
    }

    private suspend fun export(name: String, questions: List<Question>): File =
        File("build/fill-blank-pdf-validation/$name.pdf").also {
            writeSelectableQuestionPdf(it, PdfExportDetails("填空题样例", "", "填空题"), questions)
        }

    private fun text(pdf: PdfDocument) = (1..pdf.numberOfPages).joinToString("\n") {
        PdfTextExtractor.getTextFromPage(pdf.getPage(it))
    }

    private fun question(id: String, stem: String, options: JsonElement, answer: String = "", plain: String? = null): Question =
        Json.decodeFromJsonElement(Question.serializer(), buildJsonObject {
            put("questionid", id)
            put("questiontypename", "填空题")
            put("subjecthtml_svg", encode(stem))
            put("answerhtml_svg", if (answer.isBlank()) "" else encode(answer))
            put("answer", plain?.let(::JsonPrimitive) ?: JsonNull)
            put("options", options)
        })

    private fun option(html: String, correct: Int? = null) = buildJsonObject {
        correct?.let { put("istrue", it) }
        put("questionoptionhtml_svg", encode(html))
    }

    private fun encode(text: String): String {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
}
