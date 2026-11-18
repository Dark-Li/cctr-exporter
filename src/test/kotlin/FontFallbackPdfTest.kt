import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontFallbackPdfTest {
    @Test
    fun fallsBackForSymbolsMissingFromDeclaredAndBundledFonts() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/fallback.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("字体回退"), listOf(
            question("1", "<p><span style='font-family:Calibri'>中断∼响应</span></p>"),
            question("2", "<p><span style='font-family:Times New Roman'>A∼B</span></p>"),
            question("3", "<p><span style='font-family:Calibri'>P = F∙v</span></p>"),
        ))
        assertEquals(3, result.exportedCount)
        assertTrue(result.skippedIds.isEmpty())
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("中断∼响应"), text)
            assertTrue(text.contains("A∼B"), text)
            assertTrue(text.contains("P = F∙v"), text)
            assertTrue(pageFonts(pdf).any { it.contains("SegoeUISymbol") }, pageFonts(pdf).toString())
        }
    }

    @Test
    fun keepsTimesGlyphWhenFamilyCarriesInvisibleCharacters() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/times-normalized.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("字体名归一化"), listOf(
            Question(
                "1", "计算题", encode("<p>带传动计算</p>"),
                encode("<p><span style='font-family:Times New Roman\u200B'>P = F∙v</span></p>"),
                null, JsonArray(emptyList()),
            ),
            question("2", "<p><span style='font-family:Times\u00A0New\u00A0Roman'>Hello</span></p>"),
        ))
        assertEquals(2, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            assertTrue(pageFonts(pdf).any { it.contains("TimesNewRomanPSMT") }, pageFonts(pdf).toString())
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("P = F∙v"), text)
            assertTrue(text.contains("Hello"), text)
        }
    }

    @Test
    fun keepsFallbackSymbolsExtractable() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/extraction.pdf")
        writeSelectableQuestionPdf(output, PdfExportDetails("符号提取"), listOf(
            question("1", "<p><span style='font-family:Times New Roman'>A⌢B</span></p>"),
            question("2", "<p><span style='font-family:Calibri'>C˙D</span></p>"),
        ))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("A⌢B"), text)
            assertTrue(text.contains("C˙D"), text)
            assertFalse(text.contains('\uFFFD'), text)
        }
    }

    @Test
    fun coversHeadingsPlainAnswersAndPageFurniture() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/furniture.pdf")
        val result = writeSelectableQuestionPdf(
            output,
            PdfExportDetails("机械设计∙2026", "带传动∼链传动", "计算题"),
            listOf(
                Question("1", "简答题", encode("<p>普通题干</p>"), "", "F∙v∼常数", JsonArray(emptyList())),
            ),
        )
        assertEquals(1, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("∙") && text.contains("∼"), text)
            assertTrue(text.contains("F∙v∼常数"), text)
        }
    }

    @Test
    fun keepsFallbackStyleRunsRenderable() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/styles.pdf")
        writeSelectableQuestionPdf(output, PdfExportDetails("回退样式"), listOf(
            question("1", "<p><b><span style='font-family:Calibri'>A∼B</span></b> <i><span style='font-family:Calibri'>C∙D</span></i></p>"),
        ))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("A∼B") && text.contains("C∙D"), text)
        }
    }

    @Test
    fun keepsFallbackSymbolsInsideNarrowTableCells() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/table.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("表格回退"), listOf(
            question("1", "<table><tr><td style='width:60px'><span style='font-family:Calibri'>A∙B∙C∙D∙E∙F∙G∙H</span></td><td>说明</td></tr></table>"),
        ))
        assertEquals(1, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("∙"), text)
            assertTrue(text.contains("说明"), text)
        }
    }

    @Test
    fun reportsTriedFontsWhenNoCandidateCovers() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/unsupported.pdf")
        val rare = String(Character.toChars(0x2B739))
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("无字体覆盖"), listOf(
            question("1", "<p>第一题正常</p>"),
            question("2", "<p>$rare</p>"),
        ))
        assertEquals(1, result.exportedCount)
        val failure = result.failureMessages.single()
        assertTrue(failure.contains("U+2B739"), failure)
        assertTrue(failure.contains("SourceHanSansCN(无字形)"), failure)
        assertTrue(failure.contains("Segoe UI Symbol(无字形)"), failure)
        assertTrue(failure.contains("Arial Unicode MS"), failure)
    }

    @Test
    fun keepsSoftHyphenAndZeroWidthSpaceWithoutFailing() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/format-controls.pdf")
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("格式控制"), listOf(
            question("1", "<p>齿\u00AD轮\u200B传动</p>"),
            question("2", "<p>结果\u2061示例</p>"),
        ))
        assertEquals(2, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("齿轮传动"), text)
            assertTrue(text.contains("结果示例"), text)
            val actual = PdfTextExtractor.getTextFromPage(
                pdf.getPage(1), com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy().setUseActualText(true),
            )
            assertTrue(actual.contains('\u00AD') && actual.contains('\u200B') && actual.contains('\u2061'), actual)
        }
    }

    @Test
    fun keepsSoftHyphenInFillBlankAnswers() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/fill-blank-soft-hyphen.pdf")
        val options = JsonArray(listOf(
            JsonObject(mapOf(
                "istrue" to JsonPrimitive(1),
                "questionoptionhtml_svg" to JsonPrimitive(encode("<p>齿\u00AD轮</p>")),
            )),
        ))
        val input = Question("1", "填空题", encode("<p>填空：齿\u00AD轮传动</p>"), "", null, options)
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("填空格式控制"), listOf(input))
        assertEquals(1, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("齿轮传动"), text)
            assertTrue(text.contains("齿轮"), text)
        }
    }

    @Test
    fun handlesAnyInvisibleFormatCharacterByCategory() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/any-format.pdf")
        val anchor = "\uFFF9"
        val tagSpace = String(Character.toChars(0xE0020))
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("任意格式控制"), listOf(
            question("1", "<p>前${anchor}中${tagSpace}后</p>"),
        ))
        assertEquals(1, result.exportedCount)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("前中后"), text)
            val actual = PdfTextExtractor.getTextFromPage(
                pdf.getPage(1), com.itextpdf.kernel.pdf.canvas.parser.listener.LocationTextExtractionStrategy().setUseActualText(true),
            )
            assertTrue(actual.contains(anchor) && actual.contains(tagSpace), actual)
        }
    }

    @Test
    fun skipsOnlyQuestionWithUnknownPrivateUseGlyph() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/isolation.pdf")
        val secret = "不应出现在诊断里的文本"
        val result = writeSelectableQuestionPdf(output, PdfExportDetails("逐题隔离"), listOf(
            question("1", "<p>第一题正常</p>"),
            question("2", "<p><font face='MissingFixtureFont'>$secret&#xF070;</font></p>"),
            question("3", "<p>第三题正常</p>"),
        ))
        assertEquals(2, result.exportedCount)
        assertEquals(listOf("2"), result.skippedIds)
        val failure = result.failureMessages.single()
        assertTrue(failure.contains("U+F070"), failure)
        assertTrue(failure.contains("阶段=题干"), failure)
        assertTrue(failure.contains("源字体=MissingFixtureFont"), failure)
        assertFalse(failure.contains(secret), failure)
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = PdfTextExtractor.getTextFromPage(pdf.getPage(1))
            assertTrue(text.contains("第一题正常") && text.contains("第三题正常"), text)
            assertFalse(text.contains(secret), text)
            assertEquals(2, pdf.getOutlines(false).allChildren.size)
        }
    }

    @Test
    fun stillFailsFastForCaptureErrors() = runBlocking {
        val output = File("build/font-fallback-pdf-validation/capture.pdf")
        val error = assertFailsWith<IllegalStateException> {
            writeSelectableQuestionPdf(output, PdfExportDetails("截图失败"), listOf(
                question("1", "<p>题图<img src='https://example.com/a.png'/></p>"),
            ))
        }
        assertTrue(error.message.orEmpty().contains("Edge"), error.message)
    }

    private fun pageFonts(pdf: PdfDocument): Set<String> {
        val resources = pdf.getPage(1).resources
        val fonts: PdfDictionary = resources.getResource(PdfName.Font) ?: return emptySet()
        return fonts.keySet().mapNotNull { key ->
            fonts.getAsDictionary(key)?.let { dict ->
                runCatching { PdfFontFactory.createFont(dict).fontProgram.fontNames.fontName }.getOrNull()
            }
        }.toSet()
    }

    private fun question(id: String, stem: String) =
        Question(id, "简答题", encode(stem), encode("答案"), null, JsonArray(emptyList()))

    private fun encode(value: String): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
