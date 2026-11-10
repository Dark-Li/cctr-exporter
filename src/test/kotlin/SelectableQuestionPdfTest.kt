import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import top.kagg886.cctr.driver.CapturedPdfAsset
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class SelectableQuestionPdfTest {
    @Test
    fun exportsSelectableStemOptionsAndCorrectAnswersInSourceOrder() = runBlocking {
        val output = File("build/selectable-pdf-validation/choices.pdf")
        val single = question("2", "单选题", "<p>第一页：选择正确答案 &amp; 核对 &lt;值&gt;。</p>", """
            {"B":${option("<span>第一项</span>", true)},"D":${option("D. 第二项", false)}}
        """.trimIndent())
        val multiple = question("10", "多选题", "选择全部匹配项。", """
            [${option("A 股是第一项", false)},${option("乙项", true)},${option("丙项", false)},${option("丁项", true)}]
        """.trimIndent())
        val previousDuplicate = question("2", "单选题", "被覆盖的旧题目版本", "[]")
        writeSelectableQuestionPdf(output, PdfExportDetails("数据库系统", "复习", "选择题"), listOf(multiple, previousDuplicate, single))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            assertTrue(text.contains("第一页：选择正确答案 & 核对 <值>。"), text)
            assertTrue(text.indexOf("第一项") in 0 until text.indexOf("第二项"), text)
            assertTrue(text.indexOf("第二项") in 0 until text.indexOf("选择全部匹配项"), text)
            assertTrue(text.lineSequence().any { it.trim() == "参考答案：B" }, text)
            assertTrue(text.contains("D. 第二项"), text)
            assertFalse(text.contains("D. D."), text)
            assertTrue(text.contains("A 股是第一项"), text)
            assertTrue(text.lineSequence().any { it.trim() == "参考答案：B、D" }, text)
            assertFalse(text.contains("被覆盖的旧题目版本"), text)
            assertEquals(2, pdf.getOutlines(false).allChildren.size)
            assertEquals(2, pdf.catalog.getNameTree(PdfName.Dests).names.size)
            assertEquals(595f, pdf.getPage(1).pageSize.width, 0.1f)
        }
    }

    @Test
    fun keepsJudgmentSemanticsAndFormatsSubjectiveAnswerTable() = runBlocking {
        val output = File("build/selectable-pdf-validation/answers.pdf")
        val judgment = question("3", "判断题", "该命题是否成立？", "[${option("正确", false)},${option("错误。", true)}]")
        val subjective = question("4", "主观题", "写出判断过程。", "[]", """
            <p>解释如下<br>第一步。H<sub>2</sub>O 与 x<sup>2</sup>。</p>
            <table><tr><th colspan="2">结果表</th></tr><tr><td rowspan="2">组甲</td><td>通过</td></tr><tr><td>复核</td></tr></table>
        """.trimIndent())
        writeSelectableQuestionPdf(output, PdfExportDetails("示例课程"), listOf(judgment, subjective))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            listOf("参考答案：错误", "解释如下", "第一步", "结果表", "组甲", "通过", "复核").forEach {
                assertTrue(text.contains(it), text)
            }
        }
    }

    @Test
    fun sendsOnlyTheEmbeddedFontFormulaToTheFallbackRenderer() = runBlocking {
        val output = File("build/selectable-pdf-validation/fallback.pdf")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="30" height="20"><style>@font-face{font-family:math;src:url(data:font/ttf;base64,AA==)}</style><text x="2" y="17" font-family="math">x</text></svg>"""
        val data = Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        val input = question("7", "主观题", "<p>内联公式之前<img width='30' height='20' src='data:image/svg+xml;base64,$data'>内联公式之后</p>", "[]")
        val captures = mutableListOf<String>()
        writeSelectableQuestionPdf(output, PdfExportDetails("公式回退"), listOf(input)) { fragment ->
            captures.add(fragment)
            val picture = BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply {
                    color = Color.WHITE
                    fillRect(0, 0, width, height)
                    color = Color.BLACK
                    drawLine(8, 7, 50, 33)
                    drawLine(8, 33, 50, 7)
                    dispose()
                }
            }
            CapturedPdfAsset(picture, 30, 20)
        }
        assertEquals(1, captures.size)
        assertTrue(captures.single().startsWith("<img"))
        assertFalse(captures.single().contains("内联公式之前"))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            assertTrue(text.indexOf("内联公式之前") in 0 until text.indexOf("内联公式之后"), text)
            assertTrue(pdf.getPage(1).resources.getResource(PdfName.XObject).keySet().isNotEmpty())
        }
    }

    @Test
    fun sendsSvgTextAndRelativeSizesToEdgeWithoutRasterizingAdjacentText() = runBlocking {
        val output = File("build/formula-pdf-validation/formula-routing.pdf").apply { parentFile.mkdirs() }
        val textFormula = """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="48"><text x="4" y="32">Aℝ𝑥B</text></svg>"""
        val relativeFormula = """<svg xmlns="http://www.w3.org/2000/svg" width="4em" height="2em" viewBox="0 0 64 32"><path d="M1 1L63 31" stroke="black"/></svg>"""
        val mathMl = """<math><mfrac><msup><mi>x</mi><mn>2</mn></msup><msub><mi>y</mi><mn>1</mn></msub></mfrac></math>"""
        val uri = "data:image/svg+xml;charset=utf8," + java.net.URLEncoder.encode(textFormula, Charsets.UTF_8.name()).replace("+", "%20")
        val input = question("1", "主观题", "<p>原生题干前<img width='120' height='48' src='$uri'>原生题干后</p><p>$relativeFormula</p>", "[]", "<p>答案前$mathMl 答案后</p>")
        val fragments = mutableListOf<String>()
        writeSelectableQuestionPdf(output, PdfExportDetails("公式局部渲染"), listOf(input)) { fragment ->
            fragments.add(fragment)
            val picture = BufferedImage(240, 96, BufferedImage.TYPE_INT_RGB).apply { setRGB(10, 10, Color.RED.rgb) }
            CapturedPdfAsset(picture, 120, 48)
        }
        assertEquals(3, fragments.size)
        assertTrue(fragments[0].contains("data:image/svg+xml;charset=utf8"))
        assertTrue(fragments[1].startsWith("<svg"))
        assertTrue(fragments[2].contains("<mfrac>"))
        assertTrue(fragments.none { it.contains("原生题干") || it.contains("答案后") })
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            assertTrue(text.indexOf("原生题干前") in 0 until text.indexOf("原生题干后"), text)
            assertTrue(text.indexOf("答案前") in 0 until text.indexOf("答案后"), text)
        }
    }

    @Test
    fun preservesInlineFormulaAndLongTextWithoutReplacingEntireQuestion() = runBlocking {
        val output = File("build/selectable-pdf-validation/mixed.pdf")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="44" height="20"><path d="M2 10h40m-20-8v16" stroke="black"/></svg>"""
        val formula = Base64.getEncoder().encodeToString(svg.toByteArray())
        val stem = "<p>公式前<img src='data:image/svg+xml;base64,$formula' width='44' height='20'>公式后</p>" +
            (1..90).joinToString("") { "<p>第 ${it} 步，保留这一段可复制的说明文字。</p>" }
        writeSelectableQuestionPdf(output, PdfExportDetails("长题测试"), listOf(question("8", "主观题", stem, "null")))
        PdfDocument(PdfReader(output)).use { pdf ->
            val text = extract(pdf)
            assertTrue(pdf.numberOfPages >= 2)
            assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(2)).contains("第 1 题 · 续"))
            assertTrue(text.indexOf("公式前") in 0 until text.indexOf("公式后"), text)
            assertTrue(text.contains("第 90 步"), text)
            assertTrue(text.contains("平台未提供"), text)
            val firstPageObjects = pdf.getPage(1).resources.getResource(PdfName.XObject)
            assertTrue(firstPageObjects?.keySet()?.isNotEmpty() == true)
        }
    }

    @Test
    fun keepsRasterAspectRatioAndTextInMultiPageTable() = runBlocking {
        val output = File("build/selectable-pdf-validation/table-and-image.pdf")
        val picture = BufferedImage(640, 160, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, width, height)
                color = Color(43, 86, 109)
                for (index in 0..5) fillRect(16 + index * 100, 140 - index * 17, 64, 10 + index * 17)
                dispose()
            }
        }
        val encoded = ByteArrayOutputStream().also { ImageIO.write(picture, "png", it) }.toByteArray()
        val dataUri = "data:image/png," + encoded.joinToString("") { "%%%02X".format(it.toInt() and 255) }
        val rows = (1..42).joinToString("") { "<tr><td>数据行 $it</td><td>可复制单元格</td></tr>" }
        val stem = "<p>此表含配图与文字。</p><table><tr><th>编号</th><th>观察值</th></tr>" +
            "<tr><td><img src='$dataUri' width='640'></td><td>图例说明</td></tr>$rows</table><p>末尾文字。</p>"
        writeSelectableQuestionPdf(output, PdfExportDetails("表格和配图"), listOf(question("12", "主观题", stem, "[]")))
        PdfDocument(PdfReader(output)).use { pdf ->
            assertTrue(pdf.numberOfPages in 2..4, "A wide image must not compress the other column into one character per line")
            val text = extract(pdf)
            assertTrue(text.contains("数据行 42"), text)
            assertTrue(text.contains("末尾文字。"), text)
            val imageRatios = mutableListOf<Float>()
            val parser = PdfCanvasProcessor(object : IEventListener {
                override fun getSupportedEvents() = setOf(EventType.RENDER_IMAGE)
                override fun eventOccurred(data: IEventData, type: EventType) {
                    val matrix = (data as ImageRenderInfo).imageCtm
                    imageRatios.add(matrix.get(0) / matrix.get(4))
                }
            })
            for (number in 1..pdf.numberOfPages) {
                parser.reset()
                parser.processPageContent(pdf.getPage(number))
            }
            assertEquals(1, imageRatios.size)
            assertEquals(4f, imageRatios.single(), 0.05f)
        }
    }

    @Test
    fun brokenResourcePreservesExistingPdf() = runBlocking {
        val output = File("build/selectable-pdf-validation/bad.pdf").apply { parentFile.mkdirs(); writeText("previous result") }
        val broken = question("42", "单选题", "<p>题干<img src='data:image/png;base64,broken'></p>", "[]")
        assertFailsWith<Exception> { writeSelectableQuestionPdf(output, PdfExportDetails("测试"), listOf(broken)) }
        assertEquals("previous result", output.readText())
    }

    private fun extract(pdf: PdfDocument) = (1..pdf.numberOfPages).joinToString("\n") {
        PdfTextExtractor.getTextFromPage(pdf.getPage(it))
    }

    private fun question(id: String, type: String, stem: String, options: String, answer: String = ""): Question = Json.decodeFromString(
        """{"questionid":"$id","questiontypename":"$type","subjecthtml_svg":"${encode(stem)}","answerhtml_svg":"${if (answer.isBlank()) "" else encode(answer)}","answer":null,"options":$options}"""
    )

    private fun option(html: String, correct: Boolean) = """{"istrue":${if (correct) 1 else 0},"questionoptionhtml_svg":"${encode(html)}"}"""

    private fun encode(text: String): String {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }
}
