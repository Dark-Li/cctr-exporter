import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.PdfInstalledFonts
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import top.kagg886.cctr.driver.CapturedPdfAsset
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceFontQuestionPdfTest {
    @Test
    fun mapsOnlyVerifiedSymbolCodesAcrossInheritedHtmlFonts() = runBlocking {
        val output = File("build/source-font-pdf-validation/symbol.pdf")
        val stem = "<style>.legacy{font-family:Symbol}</style><p>题干前<span class='legacy'><i>&#xF070;</i> &#xF0BB;</span>题干后</p>"
        val answer = "<p>答文前<font face='Symbol'><span>&#xF070;</span></font>答文后</p>"
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("源字体样例"), listOf(question(stem, answer)))
        assertEquals(1, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("π") && text.contains("≈"), text)
            assertTrue(text.contains("题干前") && text.contains("题干后"), text)
            assertTrue(text.contains("答文前") && text.contains("答文后"), text)
            assertFalse(text.contains('\uF070') || text.contains('\uF0BB'))
        }
    }

    @Test
    fun reportsUnknownSourceFontsWithoutLeakingQuestionOrAnswerText() = runBlocking {
        val output = File("build/source-font-pdf-validation/unsupported.pdf").apply {
            parentFile.mkdirs()
            writeText("previous result")
        }
        val secret = "本段不应出现在诊断日志的文本"
        val error = assertFailsWith<IllegalStateException> {
            writeSelectableQuestionPdf(output, PdfExportDetails("源字体诊断"), listOf(
                question("正常题干", "<p><font face='MissingFixtureFont'>$secret&#xF070;</font></p>"),
            ))
        }
        val details = error.message.orEmpty()
        assertTrue(details.contains("U+F070"), details)
        assertTrue(details.contains("阶段=参考答案"), details)
        assertTrue(details.contains("源字体=MissingFixtureFont"), details)
        assertFalse(details.contains(secret), details)
        assertEquals("previous result", output.readText())
    }

    @Test
    fun refusesUnverifiedOverridesAndMapsSymbolsInsideOptions() = runBlocking {
        val output = File("build/source-font-pdf-validation/choices.pdf")
        val options = JsonArray(listOf(
            JsonObject(mapOf("istrue" to JsonPrimitive(1), "questionoptionhtml_svg" to JsonPrimitive(encode(
                "<span style='font-family:Symbol'>&#xF0BB;</span><span>正确选项</span>",
            )))),
            JsonObject(mapOf("istrue" to JsonPrimitive(0), "questionoptionhtml_svg" to JsonPrimitive(encode("其他选项")))),
        ))
        val input = Question("76141142", "单选题", encode("普通字母 p 和近似符号"), "", null, options)
        writeSelectableQuestionPdf(output, PdfExportDetails("选项来源字体"), listOf(input))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("A. ≈") && text.contains("参考答案：A"), text)
            assertTrue(text.contains("字母 p"), text)
        }
        for (markup in listOf(
            "<span style='font-family:Symbol'><span style='font:16px UnknownFont'>&#xF070;</span></span>",
            "<span style='font-family:Symbol;font:16px UnknownFont'>&#xF070;</span>",
            "<span style='font-family:MissingFixtureFont,Symbol'>&#xF070;</span>",
            "<style>[data-x]{font-family:OtherFont}</style><span style='font-family:Symbol'><span data-x>&#xF070;</span></span>",
            "<style>@font-face{font-family:Symbol;src:url(data:font/ttf;base64,AA==)}</style><span style='font-family:Symbol'>&#xF070;</span>",
        )) {
            val error = assertFailsWith<IllegalStateException> {
                writeSelectableQuestionPdf(File("build/source-font-pdf-validation/unverified.pdf"), PdfExportDetails("来源不确定"),
                    listOf(question(markup, "普通答案")))
            }
            assertTrue(error.message.orEmpty().contains("U+F070"), error.message)
            assertTrue(error.message.orEmpty().contains("阶段=题干"), error.message)
        }
    }

    @Test
    fun preservesVerifiedWingdingsLocallyWithNativeAdjacentTextAndPlaceholderForMissingGlyphs() = runBlocking {
        org.junit.Assume.assumeTrue(File(System.getenv("WINDIR").orEmpty(), "Fonts/wingding.ttf").isFile)
        val fragments = mutableListOf<String>()
        val output = File("build/source-font-pdf-validation/local-font.pdf")
        writeSelectableQuestionPdf(output, PdfExportDetails("局部原字体"), listOf(
            question("<p>原生正文前<u><font face='Wingdings'>&#xF070;&#xF0BB;</font></u>原生正文后</p>", "原生答案"),
        )) { fragment ->
            fragments.add(fragment)
            val image = BufferedImage(40, 48, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(15, 15, 0xFF000000.toInt())
            CapturedPdfAsset(image, 20, 24, 16f, 4f, 7f)
        }
        assertEquals(1, fragments.size)
        assertTrue(fragments.single().contains("data-cctr-font-family=\"Wingdings\""))
        assertTrue(fragments.single().contains("text-decoration:underline"))
        assertTrue(fragments.single().contains("\uF070\uF0BB"))
        assertFalse(fragments.single().contains("原生正文"))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("原生正文前") && text.contains("原生正文后") && text.contains("原生答案"), text)
            assertFalse(text.contains("π"), text)
        }
        val missingOutput = File("build/source-font-pdf-validation/missing-glyph.pdf")
        val missing = writeSelectableQuestionPdf(missingOutput, PdfExportDetails("缺字"),
            listOf(question("<font face='Symbol'>&#xF0FF;</font>", "答案"))) { error("must not capture .notdef") }
        assertEquals(1, missing.exportedCount)
        assertTrue(missing.warningMessages.single().contains("U+F0FF"), missing.warningMessages.single())
        PdfDocument(PdfReader(missingOutput)).use { pdf ->
            assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("\uFFFD"), "placeholder missing")
        }
    }

    @Test
    fun capturesWingdingsThreeGlyphWithItsOwnFamily() = runBlocking {
        org.junit.Assume.assumeTrue(File(System.getenv("WINDIR").orEmpty(), "Fonts/WINGDNG3.TTF").isFile)
        val fragments = mutableListOf<String>()
        val output = File("build/source-font-pdf-validation/wingdings3.pdf")
        writeSelectableQuestionPdf(output, PdfExportDetails("局部原字体"), listOf(
            question("<p>原生正文前<font face='Wingdings 3'>&#xF072;</font>原生正文后</p>", "原生答案"),
        )) { fragment ->
            fragments.add(fragment)
            CapturedPdfAsset(BufferedImage(40, 48, BufferedImage.TYPE_INT_ARGB), 20, 24, 16f, 4f, 7f)
        }
        assertEquals(1, fragments.size)
        assertTrue(fragments.single().contains("data-cctr-font-family=\"Wingdings 3\""), fragments.single())
        assertTrue(fragments.single().contains("\uF072"), fragments.single())
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("原生正文前") && text.contains("原生正文后"), text)
        }
    }

    @Test
    fun fallsBackToTheNextDeclaredFamilyWhenTheFirstFontLacksTheGlyph() = runBlocking {
        val windows = System.getenv("WINDIR").orEmpty()
        org.junit.Assume.assumeTrue(File(windows, "Fonts/simsun.ttc").isFile)
        org.junit.Assume.assumeTrue(File(windows, "Fonts/WINGDNG3.TTF").isFile)
        val simsun = PdfInstalledFonts.resolve("SimSun")
        org.junit.Assume.assumeTrue(simsun != null && !PdfInstalledFonts.hasGlyph(simsun, 0xF0B4))
        val fragments = mutableListOf<String>()
        val output = File("build/source-font-pdf-validation/stack-fallback.pdf")
        writeSelectableQuestionPdf(output, PdfExportDetails("字体栈回退"), listOf(
            question("<p>前<span style=\"font-family:SimSun,'Wingdings 3'\">&#xF0B4;</span>后</p>", "答案"),
        )) { fragment ->
            fragments.add(fragment)
            CapturedPdfAsset(BufferedImage(40, 48, BufferedImage.TYPE_INT_ARGB), 20, 24, 16f, 4f, 7f)
        }
        assertEquals(1, fragments.size)
        assertTrue(fragments.single().contains("data-cctr-font-family=\"Wingdings 3\""), fragments.single())
        assertFalse(fragments.single().contains("data-cctr-font-family=\"SimSun\""), fragments.single())
        assertTrue(fragments.single().contains("\uF0B4"), fragments.single())
    }

    @Test
    fun rejectsMissingFirstFamilyEvenWhenALaterFamilyHasTheGlyph() = runBlocking {
        val error = assertFailsWith<IllegalStateException> {
            writeSelectableQuestionPdf(File("build/source-font-pdf-validation/missing-first.pdf"), PdfExportDetails("首字体缺失"),
                listOf(question("<span style=\"font-family:MissingFixtureFont,'Wingdings 3'\">&#xF072;</span>", "答案"))) {
                error("首字体缺失时不应截图")
            }
        }
        assertTrue(error.message.orEmpty().contains("首字体未安装"), error.message)
        assertTrue(error.message.orEmpty().contains("U+F072"), error.message)
    }

    @Test
    fun rendersPlaceholderWhenSimSunHasNoGlyphAndNoFallback() = runBlocking {
        val windows = System.getenv("WINDIR").orEmpty()
        org.junit.Assume.assumeTrue(File(windows, "Fonts/simsun.ttc").isFile)
        val simsun = PdfInstalledFonts.resolve("SimSun")
        org.junit.Assume.assumeTrue(simsun != null && !PdfInstalledFonts.hasGlyph(simsun, 0xF0B4))
        val output = File("build/source-font-pdf-validation/simsun-only.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("无回退"),
            listOf(question("<span style=\"font-family:SimSun\">&#xF0B4;</span>", "答案"))) {
            error("SimSun 缺字时不应截图")
        }
        assertEquals(1, result.exportedCount)
        assertTrue(result.skippedIds.isEmpty(), result.skippedIds.toString())
        val warning = result.warningMessages.single()
        assertTrue(warning.contains("U+F0B4"), warning)
        assertTrue(warning.contains("字体栈=SimSun"), warning)
        assertTrue(warning.contains("已用 � 替代"), warning)
        PdfDocument(PdfReader(output)).use { pdf ->
            assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains("\uFFFD"), "placeholder missing")
        }
    }

    private fun question(stem: String, answer: String) =
        Question("42159570", "简答题", encode(stem), encode(answer), null, JsonArray(emptyList()))

    private fun encode(value: String): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
