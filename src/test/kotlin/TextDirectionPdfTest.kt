import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.backend.task.writeQuestionPdfArchive
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import kotlin.test.*

class TextDirectionPdfTest {
    @Test
    fun dropsInvisibleDirectionMarksWithoutLosingText() = runBlocking {
        val output = File("build/text-direction-pdf-validation/rlm.pdf").apply { parentFile.mkdirs() }
        val stem = "<p>题干\u200F保留，10Base-5。</p>"
        val answer = "<p>答案\u200E与\u200F都保留。</p>"
        val result = writeSelectableQuestionPdf(
            output,
            PdfExportDetails("方向标记"),
            listOf(question("1", "判断题", stem, answer)),
        )
        assertEquals(1, result.exportedCount)
        assertTrue(result.skippedIds.isEmpty(), result.skippedIds.toString())
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            assertTrue(text.contains("题干保留，10Base-5。"), text)
            assertTrue(text.contains("答案与都保留。"), text)
        }
    }

    @Test
    fun skipsOnlyTheUnsupportedRtlQuestion() = runBlocking {
        val output = File("build/text-direction-pdf-validation/rtl.pdf").apply { parentFile.mkdirs() }
        val bad = question("2", "判断题", "<p>مرحبا بالعالم</p>", "")
        val good = question("3", "判断题", "<p>正常题干。</p>", "<p>正常答案。</p>")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("方向标记"), listOf(bad, good))
        assertEquals(1, result.exportedCount)
        assertEquals(listOf("2"), result.skippedIds)
        assertEquals(1, result.failureMessages.size)
        assertTrue(result.failureMessages.single().contains("双向文本"), result.failureMessages.single())
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            assertTrue(text.contains("正常题干。"), text)
            assertTrue(text.contains("正常答案。"), text)
        }
    }

    @Test
    fun keepsArchiveWhenOneGroupFails() = runBlocking {
        val archive = File("build/text-direction-pdf-validation/partial.zip").apply {
            parentFile.mkdirs()
            delete()
        }
        val reports = mutableListOf<String>()
        val skipped = writeQuestionPdfArchive(
            archive = archive,
            groups = listOf(
                PdfExportDetails("方向标记", "分组一", "判断题") to
                    listOf(question("10", "判断题", "<p>مرحبا</p>", "")),
                PdfExportDetails("方向标记", "分组二", "判断题") to
                    listOf(question("11", "判断题", "<p>可导出题干。</p>", "<p>可导出答案。</p>")),
            ),
            reportSkipped = { reports += it },
            renderComplex = { error("本题不应触发局部渲染") },
        )
        assertTrue(archive.isFile && archive.length() > 0, "failed group must not discard the archive")
        assertEquals(1, skipped)
        assertTrue(reports.any { it.contains("整组未导出") }, reports.toString())
        ZipFile(archive).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.any { it.endsWith("分组二/判断题.pdf") }, names.toString())
        }
    }

    @Test
    fun reportsPlaceholderWarningsWithoutSkippingTheQuestion() = runBlocking {
        val windows = System.getenv("WINDIR").orEmpty()
        org.junit.Assume.assumeTrue(File(windows, "Fonts/simsun.ttc").isFile)
        val simsun = top.kagg886.cctr.desktop.util.PdfInstalledFonts.resolve("SimSun")
        org.junit.Assume.assumeTrue(simsun != null && !top.kagg886.cctr.desktop.util.PdfInstalledFonts.hasGlyph(simsun, 0xF0B4))
        val archive = File("build/text-direction-pdf-validation/placeholder.zip").apply {
            parentFile.mkdirs()
            delete()
        }
        val reports = mutableListOf<String>()
        val skipped = writeQuestionPdfArchive(
            archive = archive,
            groups = listOf(
                PdfExportDetails("课程", "章节", "简答题") to listOf(
                    question("20", "简答题", "<p>前<span style=\"font-family:SimSun\">&#xF0B4;</span>后</p>", "<p>答案。</p>"),
                    question("21", "简答题", "<p>正常题干。</p>", "<p>正常答案。</p>"),
                ),
            ),
            reportSkipped = { reports += it },
            renderComplex = { error("本题不应触发局部渲染") },
        )
        assertEquals(0, skipped)
        assertTrue(archive.isFile && archive.length() > 0)
        assertTrue(reports.any { it.contains("已用 � 替代") }, reports.toString())
        assertTrue(reports.any { it.contains("处字符无法还原") }, reports.toString())
    }

    private fun extract(pdf: PdfDocument): String = (1..pdf.numberOfPages).joinToString("\n") {
        PdfTextExtractor.getTextFromPage(pdf.getPage(it))
    }

    private fun question(id: String, type: String, stem: String, answer: String): Question = Json.decodeFromString(
        """{"questionid":"$id","questiontypename":"$type","subjecthtml_svg":"${encode(stem)}","answerhtml_svg":"${if (answer.isBlank()) "" else encode(answer)}","answer":null,"options":""}"""
    )

    private fun encode(text: String): String {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
}
