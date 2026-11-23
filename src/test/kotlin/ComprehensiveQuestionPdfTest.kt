import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

class ComprehensiveQuestionPdfTest {
    @Test
    fun rendersEverySubQuestionStemAndAnswerInSourceOrder() = runBlocking {
        val output = File("build/comprehensive-pdf-validation/sub-questions.pdf").apply { parentFile.mkdirs() }
        val question = question(
            id = "9001",
            stem = "<p>某 10Base-5 网络中传输的比特串为 01100100，试问：</p>",
            subs = listOf(
                sub("9002", "<p>该网络的传输介质是什么？</p>", answerHtml = "<p>同轴电缆。</p>"),
                sub("9003", "<p>10Base-5 的整体含义是什么？</p>", plain = "粗缆以太网，每段最大长度 500m。"),
                sub("9004", "<p>该网络传输数据时是否会发生冲突？</p>"),
                sub("9005", "<p>请画出该比特流的信号波形。</p>", answerHtml = "<p>波形如下：</p>"),
            ),
        )
        writeSelectableQuestionPdf(output, PdfExportDetails("综合题样例", "", "综合题"), listOf(question))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = text(pdf)
            assertTrue(text.contains("某 10Base-5 网络中传输的比特串为 01100100，试问："), text)
            val positions = listOf("（1）", "（2）", "（3）", "（4）").map { text.indexOf(it) }
            assertTrue(positions.all { it >= 0 }, text)
            assertTrue(positions.zipWithNext().all { (left, right) -> left < right }, text)
            assertTrue(text.indexOf("该网络的传输介质是什么？") in 0 until text.indexOf("10Base-5 的整体含义是什么？"), text)
            assertTrue(text.indexOf("同轴电缆。") in 0 until text.indexOf("粗缆以太网，每段最大长度 500m。"), text)
            assertTrue(text.indexOf("粗缆以太网，每段最大长度 500m。") in 0 until text.indexOf("波形如下："), text)
            assertEquals(1, Regex("参考答案").findAll(text).count(), text)
            assertEquals(1, Regex("平台未提供").findAll(text).count(), text)
            assertTrue(text.contains("（3）平台未提供"), text)
            assertFalse(text.contains("参考答案：平台未提供"), text)
            assertEquals(1, pdf.getOutlines(false).allChildren.size)
        }
    }

    @Test
    fun answersChoiceSubQuestionWithItsOwnOptionLabels() = runBlocking {
        val output = File("build/comprehensive-pdf-validation/choice-sub.pdf").apply { parentFile.mkdirs() }
        val question = question(
            id = "9101",
            stem = "<p>阅读材料后作答：</p>",
            subs = listOf(
                sub(
                    "9102",
                    "<p>下列哪一项正确？</p>",
                    options = buildJsonArray {
                        add(option("A. 甲", false))
                        add(option("B. 乙", true))
                        add(option("C. 丙", false))
                    },
                ),
            ),
        )
        writeSelectableQuestionPdf(output, PdfExportDetails("综合题样例", "", "综合题"), listOf(question))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = text(pdf)
            assertTrue(text.contains("下列哪一项正确？"), text)
            assertTrue(text.contains("（1）B"), text)
            assertEquals(0, Regex("平台未提供").findAll(text).count(), text)
        }
    }

    @Test
    fun avoidsDuplicatingNumberingAlreadyInsideSubAnswers() = runBlocking {
        val output = File("build/comprehensive-pdf-validation/self-numbered.pdf").apply { parentFile.mkdirs() }
        val question = question(
            id = "9201",
            stem = "<p>按要求作答：</p>",
            subs = listOf(
                sub("9202", "<p>第一问。</p>", answerHtml = "<p>（1）先判断网络类型。</p>"),
                sub("9203", "<p>第二问。</p>", plain = "（2）再计算下一跳。"),
            ),
        )
        writeSelectableQuestionPdf(output, PdfExportDetails("综合题样例", "", "综合题"), listOf(question))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = text(pdf)
            assertTrue(text.contains("（1）先判断网络类型。"), text)
            assertTrue(text.contains("（2）再计算下一跳。"), text)
            assertFalse(text.contains("（1）（1）"), text)
            assertFalse(text.contains("（2）（2）"), text)
        }
    }

    private fun text(pdf: PdfDocument) = (1..pdf.numberOfPages).joinToString("\n") {
        PdfTextExtractor.getTextFromPage(pdf.getPage(it))
    }

    private fun question(
        id: String,
        stem: String,
        subs: List<Question> = emptyList(),
    ): Question = Json.decodeFromJsonElement(Question.serializer(), buildJsonObject {
        put("questionid", id)
        put("questiontypename", "综合题")
        put("subjecthtml_svg", encode(stem))
        put("answerhtml_svg", "")
        put("answer", "")
        put("options", buildJsonObject { put("options", "") })
        put("subquestions", buildJsonArray { subs.forEach { add(Json.encodeToJsonElement(Question.serializer(), it)) } })
    })

    private fun sub(
        id: String,
        stem: String,
        answerHtml: String = "",
        plain: String? = null,
        options: JsonElement = JsonPrimitive(""),
    ): Question = Json.decodeFromJsonElement(Question.serializer(), buildJsonObject {
        put("questionid", id)
        put("questiontypename", "主观题")
        put("subjecthtml_svg", encode(stem))
        put("answerhtml_svg", if (answerHtml.isBlank()) "" else encode(answerHtml))
        put("answer", plain?.let(::JsonPrimitive) ?: JsonNull)
        put("options", options)
    })

    private fun option(html: String, correct: Boolean) = buildJsonObject {
        put("istrue", if (correct) 1 else 0)
        put("questionoptionhtml_svg", encode(html))
    }

    private fun encode(text: String): String {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
}
