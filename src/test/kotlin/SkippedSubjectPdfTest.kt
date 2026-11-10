import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.backend.task.writeQuestionPdfArchive
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkippedSubjectPdfTest {
    @Test
    fun exportsGoodQuestionsAroundATruncatedSubjectWithContinuousNumbering() = runBlocking {
        val output = File("build/skipped-subject-pdf-validation/continued.pdf")
        val first = question("54058672", encode("<p>前一道正常题目。</p>"))
        val broken = question("54058673", "Hw==") // One byte of the gzip magic produces EOF in readHeader.
        val last = question("54058674", encode("<p>后一道正常题目。</p>"))

        val result = writeSelectableQuestionPdf(output, PdfExportDetails("题干容错样例"), listOf(last, broken, first))

        assertEquals(2, result.exportedCount)
        assertEquals(listOf("54058673"), result.skippedIds)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("共 2 题"), text)
            assertTrue(text.contains("第 1 题 · ID 54058672"), text)
            assertTrue(text.contains("第 2 题 · ID 54058674"), text)
            assertTrue(text.indexOf("前一道正常题目") in 0 until text.indexOf("后一道正常题目"), text)
            assertFalse(text.contains("54058673"), text)
            assertEquals(listOf("第 1 题 · ID 54058672", "第 2 题 · ID 54058674"),
                pdf.getOutlines(false).allChildren.map { it.title })
        }
    }

    @Test
    fun publishesGoodGroupsAndReportsEverySkippedIdInBoundedTaskLogLines() = runBlocking {
        val archive = File("build/skipped-subject-pdf-validation/continued.zip").apply {
            parentFile.mkdirs()
            writeText("previous archive")
        }
        val badIds = (54058650..54058674).map(Int::toString)
        val allBad = badIds.map { question(it, "Hw==") }
        val missingField = Json.decodeFromString<Question>("""{"questionid":"54058675","questiontypename":"单选题","answerhtml_svg":"","answer":null,"options":[]}""")
        val good = question("54058676", encode("<p>有效章节继续导出。</p>"))
        val otherGood = question("54058677", encode("<p>另一课程有效题。</p>"))
        val logs = mutableListOf<String>()
        val skipped = writeQuestionPdfArchive(
            archive,
            listOf(
                PdfExportDetails("课程一", "全坏组", "单选题") to allBad,
                PdfExportDetails("课程一", "混合组", "单选题") to listOf(missingField, question(good.id, "!"), good),
                PdfExportDetails("课程二", "有效组", "单选题") to listOf(otherGood),
            ),
            reportSkipped = { messages ->
                assertEquals("previous archive", archive.readText())
                logs.addAll(messages)
            },
            renderComplex = { error("Unexpected Edge call") },
        )
        assertEquals(26, skipped)
        assertEquals("26 道题未导出成功，已跳过", logs.first())
        assertTrue(logs.drop(1).size >= 2)
        assertTrue(logs.all { it.length <= 200 })
        assertEquals(badIds + missingField.id, logs.drop(1).flatMap { it.substringAfter("：").split("、") })
        ZipFile(archive).use { zip ->
            assertEquals(setOf("课程一/混合组/单选题.pdf", "课程二/有效组/单选题.pdf"), zip.entries().asSequence().map { it.name }.toSet())
        }
    }

    @Test
    fun reportsAllBadQuestionsWithoutCreatingOrReplacingAnArchive() = runBlocking {
        val dir = File("build/skipped-subject-pdf-validation").apply { mkdirs() }
        val previous = dir.resolve("all-bad-existing.zip").apply { writeText("previous archive") }
        val absent = dir.resolve("all-bad-new.zip")
        val groups = listOf(PdfExportDetails("课程", "全坏组", "单选题") to listOf(
            question("1", null),
            question("2", ""),
            question("3", "!"),
            question("4", Base64.getEncoder().encodeToString("not gzip".toByteArray())),
            question("5", encode(" ")),
        ))
        for (archive in listOf(previous, absent)) {
            val logs = mutableListOf<String>()
            val error = assertFailsWith<IllegalStateException> {
                writeQuestionPdfArchive(archive, groups, reportSkipped = { logs.addAll(it) }, renderComplex = { error("Unexpected Edge call") })
            }
            assertEquals("没有可导出的题目", error.message)
            assertEquals(listOf("5 道题未导出成功，已跳过", "已跳过题目 ID：1、2、3、4、5"), logs)
        }
        assertEquals("previous archive", previous.readText())
        assertFalse(absent.exists())
    }

    @Test
    fun doesNotTreatAnswerBrowserOrCancellationErrorsAsSkippableSubjects() = runBlocking {
        val archive = File("build/skipped-subject-pdf-validation/other-error.zip").apply {
            parentFile.mkdirs()
            writeText("previous archive")
        }
        val details = PdfExportDetails("课程", "章节", "单选题")
        val groups = listOf(details to listOf(
            question("2", "Hw=="),
            question("3", encode("正常题干"), "Hw=="),
        ))
        val logs = mutableListOf<String>()
        val answerError = assertFailsWith<IllegalStateException> {
            writeQuestionPdfArchive(archive, groups, reportSkipped = { logs.addAll(it) }, renderComplex = { error("Unexpected Edge call") })
        }
        assertTrue(answerError.message.orEmpty().contains("题目 ID 3"))
        assertTrue(answerError.cause?.cause is EOFException)
        val formula = listOf(details to listOf(question("4", encode("<math><mi>x</mi></math>"))))
        val browserError = assertFailsWith<IllegalStateException> {
            writeQuestionPdfArchive(archive, formula, reportSkipped = { logs.addAll(it) }, renderComplex = { throw EOFException("浏览器截图") })
        }
        assertTrue(browserError.cause?.cause is EOFException)
        assertFailsWith<CancellationException> {
            writeQuestionPdfArchive(archive, formula, reportSkipped = { logs.addAll(it) }, renderComplex = { throw CancellationException("取消") })
        }
        assertTrue(logs.isEmpty())
        assertEquals("previous archive", archive.readText())
    }

    @Test
    fun failedSkipReportLeavesExistingArchiveIntact() = runBlocking {
        val archive = File("build/skipped-subject-pdf-validation/report-error.zip").apply {
            parentFile.mkdirs()
            writeText("previous archive")
        }
        val groups = listOf(PdfExportDetails("课程", "章节", "单选题") to listOf(
            question("1", "Hw=="),
            question("2", encode("正常题干")),
        ))
        val failure = assertFailsWith<IllegalStateException> {
            writeQuestionPdfArchive(
                archive,
                groups,
                reportSkipped = { error("任务日志写入失败") },
                renderComplex = { error("Unexpected Edge call") },
            )
        }
        assertEquals("任务日志写入失败", failure.message)
        assertEquals("previous archive", archive.readText())
    }

    private fun question(id: String, stem: String?, formattedAnswer: String = "") =
        Question(id, "单选题", stem, formattedAnswer, "样例答案", JsonArray(emptyList()))

    private fun encode(html: String): String {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(html.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
}
