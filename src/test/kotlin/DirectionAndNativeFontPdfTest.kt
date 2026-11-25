import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import top.kagg886.cctr.desktop.util.createQuestionPdfFont
import top.kagg886.cctr.desktop.util.formatControlText
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectionAndNativeFontPdfTest {
    @Test
    fun treatsLrmAsZeroWidthBeforeGlyphSelectionAndKeepsNativeText() = runBlocking {
        val output = File("build/direction-font-pdf-validation/ltr.pdf")
        writeSelectableQuestionPdf(output, PdfExportDetails("方向标记"), listOf(question(
            "<p style='font-family:Times New Roman'>\u200E前文 A\u200E (12) π 中文\u200E</p>",
            "<p>答案\u200E B (34)</p>",
        ))) { error("direction markers must not be captured") }
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("前文 A (12) π 中文"), text)
            assertTrue(text.contains("答案 B (34)"), text)
            assertFalse(text.contains('\u200E'), text)
        }
    }

    @Test
    fun embedsTimesFacesWithChineseFallbackAndHonorsNearestFontFamily() = runBlocking {
        val output = File("build/direction-font-pdf-validation/times-faces.pdf")
        val html = """<p style='font-family:Symbol'><span style='font-family:Times New Roman'>Regular π 中文 <b>Bold</b> <i>Italic</i> <b><i>Both</i></b></span></p>"""
        writeSelectableQuestionPdf(output, PdfExportDetails("原字体"), listOf(question(html, "可复制答案")))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("Regular π 中文 Bold Italic Both"), text)
            val fonts = pdf.getPage(1).resources.getResource(PdfName.Font)
            val names = fonts.keySet().map { fonts.getAsDictionary(it).getAsName(PdfName.BaseFont).value }
            for (name in listOf("TimesNewRomanPSMT", "TimesNewRomanPS-BoldMT", "TimesNewRomanPS-ItalicMT", "TimesNewRomanPS-BoldItalicMT")) {
                assertTrue(names.any { it.endsWith(name) }, "$name missing: $names")
            }
            fonts.keySet().forEach { key ->
                val font = fonts.getAsDictionary(key)
                if (font.getAsName(PdfName.BaseFont).value.contains("TimesNewRoman")) {
                    assertTrue(font.containsKey(PdfName.ToUnicode))
                    val descriptor = font.getAsArray(PdfName.DescendantFonts).getAsDictionary(0).getAsDictionary(PdfName.FontDescriptor)
                    assertTrue(descriptor.containsKey(PdfName.FontFile2))
                }
            }
        }
    }

    @Test
    fun rejectsUnsupportedBidiAndDoesNotDeleteOtherFormatControls() = runBlocking {
        for (body in listOf("<p>前文\u200E مرحبا</p>", "<p>前文\u202E后文</p>",
            "<style>.r{direction:rtl}</style><p class='r'>A\u200E (12)</p>")) {
            val error = assertFailsWith<IllegalStateException> {
                writeSelectableQuestionPdf(File("build/direction-font-pdf-validation/rtl.pdf"), PdfExportDetails("方向"), listOf(question(body, "答案")))
            }
            assertTrue(error.message.orEmpty().contains("双向文本"), error.message)
        }
        val rlmOutput = File("build/direction-font-pdf-validation/rlm.pdf")
        val rlm = writeSelectableQuestionPdf(rlmOutput, PdfExportDetails("方向"), listOf(question("<p>前文\u200F后文</p>", "答案")))
        assertTrue(rlm.exportedCount == 1, rlm.failureMessages.toString())
        PdfDocument(PdfReader(rlmOutput)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("前文后文"), text)
        }
        val unknownOutput = File("build/direction-font-pdf-validation/unknown.pdf")
        val unknown = writeSelectableQuestionPdf(unknownOutput, PdfExportDetails("未知源"),
            listOf(question("<font face='Times New Roman'>&#xF070;</font>", "答案")))
        assertTrue(unknown.exportedCount == 1, unknown.failureMessages.toString())
        assertTrue(unknown.warningMessages.single().contains("U+F070"), unknown.warningMessages.single())
        PdfDocument(PdfReader(unknownOutput)).use { pdf ->
            assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("\uFFFD"), "placeholder missing")
        }
    }

    @Test
    fun skipsOnlyQuestionWithUnsupportedDirectionText() = runBlocking {
        val output = File("build/direction-font-pdf-validation/rtl-skip.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("方向隔离"), listOf(
            question("正常题干一", "答案"),
            question("\u05E9\u05DC\u05D5\u05DD", "答案").copy(id = "42732303"),
            question("正常题干三", "答案").copy(id = "42732304"),
        ))
        assertEquals(2, result.exportedCount)
        assertEquals(listOf("42732303"), result.skippedIds)
        assertTrue(result.failureMessages.single().contains("双向文本"), result.failureMessages.single())
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("正常题干一") && text.contains("正常题干三"), text)
        }
    }

    @Test
    fun preservesZeroWidthControlsInNativeAnswersAndActualText() = runBlocking {
        val output = File("build/direction-font-pdf-validation/joining-controls.pdf")
        val expected = "答案前中\u200C文 A\u200DB π\u2060x C\uFEFFD答案后"
        writeSelectableQuestionPdf(output, PdfExportDetails("零宽排版控制"), listOf(question("正常题干", "<p>$expected</p>"))) {
            error("zero-width controls must not capture glyph images")
        }
        PdfDocument(PdfReader(output)).use { pdf ->
            val plain = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(plain.contains("答案前中文 AB πx CD答案后"), plain)
            val actual = PdfTextExtractor.getTextFromPage(pdf.getPage(1), LocationTextExtractionStrategy().setUseActualText(true))
            assertTrue(actual.contains(expected), actual)
        }
        val spans = File("build/direction-font-pdf-validation/joining-spans.pdf")
        writeSelectableQuestionPdf(spans, PdfExportDetails("跨标记连接符"), listOf(question("正常题干",
            "<p><span>\u200C</span>前<span>\u200D</span><b>中</b><span>\u200C</span>后</p>")))
        PdfDocument(PdfReader(spans)).use { pdf ->
            val actual = PdfTextExtractor.getTextFromPage(pdf.getPage(1), LocationTextExtractionStrategy().setUseActualText(true))
            assertTrue(actual.contains("\u200C前\u200D中\u200C后"), actual)
        }
        val paged = File("build/direction-font-pdf-validation/joining-paged.pdf")
        writeSelectableQuestionPdf(paged, PdfExportDetails("续页零宽控制"), listOf(question("原生题干", (0..149).joinToString("") {
            "<p>Line $it 甲\u200C乙 A\u200DB π\u2060x C\uFEFFD</p>"
        })))
        PdfDocument(PdfReader(paged)).use { pdf ->
            assertTrue(pdf.numberOfPages > 1)
            val actual = (1..pdf.numberOfPages).joinToString("\n") { page ->
                PdfTextExtractor.getTextFromPage(pdf.getPage(page), LocationTextExtractionStrategy().setUseActualText(true))
            }
            for (control in listOf('\u200C', '\u200D', '\u2060', '\uFEFF')) assertEquals(150, actual.count { it == control }, actual)
            assertTrue(actual.contains("Line 149"), actual)
        }
        for (control in listOf('\u2060', '\uFEFF')) {
            val narrow = File("build/direction-font-pdf-validation/no-break-${control.code}.pdf")
            PdfDocument(PdfWriter(narrow)).use { pdf ->
                Document(pdf, PageSize(120f, 180f)).use { document ->
                    document.setMargins(0f, 0f, 0f, 0f)
                    document.add(Paragraph(formatControlText("甲乙${control}丙", createQuestionPdfFont(false))).setFontSize(11f).setWidth(24f))
                }
            }
            PdfDocument(PdfReader(narrow)).use { pdf ->
                val actual = PdfTextExtractor.getTextFromPage(pdf.getPage(1), LocationTextExtractionStrategy().setUseActualText(true)).trim()
                assertEquals(1, actual.count { it == control })
                assertEquals("甲\n乙丙", actual.replace(control.toString(), ""))
            }
        }
    }

    @Test
    fun refusesContextualJoiningAndEmojiInsteadOfDiscardingJoiners() = runBlocking {
        for (body in listOf("<p>क्\u200Cष</p>", "<p><span>क्</span><span>\u200D</span><span>ष</span></p>", "<p>👩\u200D💻</p>")) {
            val error = assertFailsWith<IllegalStateException> {
                writeSelectableQuestionPdf(File("build/direction-font-pdf-validation/complex-joining.pdf"), PdfExportDetails("复杂连接"),
                    listOf(question(body, "答案")))
            }
            assertTrue(error.message.orEmpty().contains("连接排版"), error.message)
        }
        for (body in listOf("<p>甲<span>\u2060</span>乙</p>", "<font face='Times New Roman'>A\u2060中</font>")) {
            val error = assertFailsWith<IllegalStateException> {
                writeSelectableQuestionPdf(File("build/direction-font-pdf-validation/cross-run-join.pdf"), PdfExportDetails("跨片段"),
                    listOf(question(body, "答案")))
            }
            assertTrue(error.message.orEmpty().contains("禁止换行控制"), error.message)
        }
    }

    @Test
    fun doesNotReuseNativeFontCacheForUnverifiedSameNameCustomFont() = runBlocking {
        val output = File("build/direction-font-pdf-validation/custom.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("字体身份"), listOf(
            question("<font face='Times New Roman'>First</font>", "正常答案"),
            question("<style>@font-face{font-family:'Times New Roman';src:url(data:font/ttf;base64,AA==)}</style><font face='Times New Roman'>Second</font>", "正常答案").copy(id = "42732303"),
        ))
        assertEquals(1, result.exportedCount)
        assertEquals(listOf("42732303"), result.skippedIds)
        assertTrue(result.failureMessages.single().contains("自定义字体尚未验证"), result.failureMessages.single())
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("First"), text)
            assertFalse(text.contains("Second"), text)
        }
    }

    private fun question(stem: String, answer: String) =
        Question("42732302", "简答题", encode(stem), encode(answer), null, JsonArray(emptyList()))

    private fun encode(value: String): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
