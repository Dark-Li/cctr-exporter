import com.itextpdf.kernel.geom.Matrix
import com.itextpdf.kernel.geom.Vector
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import top.kagg886.cctr.driver.CapturedPdfAsset
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineFormulaBaselinePdfTest {
    @Test
    fun positionsInlineVectorFormulaByTheExplicitCssOffset() = runBlocking {
        val output = File("build/inline-formula-baseline-validation/vector.pdf")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><path d="M1 2H39M8 4V18" stroke="black"/></svg>"""
        val stem = """<p>矢量前<img width="40" height="20" style="vertical-align:-5px!important;vertical-align:0" src="${svgUri(svg)}">矢量后</p>"""
        writeSelectableQuestionPdf(output, PdfExportDetails("行内公式基线"), listOf(question(stem)))

        val measured = inspect(output)
        val baseline = measured.text.single { it.value.contains("矢量前") }.baseline
        assertEquals(baseline, measured.text.single { it.value.contains("矢量后") }.baseline, 0.02f)
        assertEquals(-3.75f, measured.images.single().bottom - baseline, 0.02f)
    }

    @Test
    fun usesSourceBaselinesAndMeasuredRasterInsetsWithoutMovingOrdinaryImages() = runBlocking {
        val output = File("build/inline-formula-baseline-validation/raster.pdf")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:wrs="http://www.wiris.com/xml/mathml-extension" width="40" height="20" wrs:baseline="15"><text y="15">x</text></svg>"""
        val uri = svgUri(svg)
        val math = "<math><mfrac><msup><mi>x</mi><mn>2</mn></msup><msub><mi>y</mi><mn>1</mn></msub></mfrac></math>"
        val stem = """
            <p>源基线<img width="80" height="40" src="$uri">同一行<img width="80" height="40" style="vertical-align:0" src="$uri">结束</p>
            <p>包装基线<span style="vertical-align:baseline"><img width="80" height="40" src="$uri"></span>结束</p>
            <p>缺省公式$math 完整</p>
            <p>普通配图<img width="18" style="vertical-align:-5px" src="${pngUri()}">结束</p>
            <p>块级公式<math display="block" style="vertical-align:-5px"><mi>x</mi></math>结束</p>
        """.trimIndent()
        writeSelectableQuestionPdf(output, PdfExportDetails("位图公式基线"), listOf(question(stem))) { fragment ->
            if (fragment.startsWith("<img")) capturedFormula(88, 48, 40f, 4f)
            else capturedFormula(48, 28, 20f, 4f)
        }

        val measured = inspect(output)
        assertEquals(6, measured.images.size)
        val baselines = listOf("源基线", "同一行", "包装基线", "缺省公式", "普通配图", "块级公式").map { label ->
            measured.text.single { it.value.contains(label) }.baseline
        }
        assertEquals(baselines[0], baselines[1], 0.02f)
        val offsets = measured.images.zip(baselines) { image, baseline -> image.bottom - baseline }
        listOf(-10.5f, -3f, -3f, -3f, 0f, 0f).forEachIndexed { index, expected ->
            assertEquals(expected, offsets[index], 0.02f, "formula image $index")
        }
    }

    @Test
    fun keepsWideFractionsIntactAndClearOfFollowingRowsAndPages() = runBlocking {
        val output = File("build/inline-formula-baseline-validation/wide-table.pdf")
        val wide = "<math style='vertical-align:-19px'><mfrac><msup><mi>x</mi><mn>2</mn></msup><msub><mi>y</mi><mn>1</mn></msub></mfrac></math>"
        val short = "<math style='vertical-align:-12px'><msub><mi>x</mi><mn>1</mn></msub></math>"
        val filler = (1..45).joinToString("") { "<p>保留可复制的分页正文第 $it 行。</p>" }
        val stem = "<p>下标前$short 下标后<br>下标下一行</p>$filler" +
            "<table><tr><td>宽式起点$wide<br>宽式下一行</td><td>相邻单元格</td></tr></table><p>最终正文</p>"
        writeSelectableQuestionPdf(output, PdfExportDetails("窄表格与跨页"), listOf(question(stem))) { fragment ->
            if (fragment.contains("mfrac")) capturedFormula(700, 84, 76f, 4f)
            else capturedFormula(42, 34, 26f, 4f)
        }

        val measured = inspect(output)
        assertTrue(measured.pages >= 2)
        assertEquals(2, measured.images.size)
        val first = measured.images.first()
        val next = measured.text.single { it.value.contains("下标下一行") }
        assertEquals(first.page, next.page)
        assertTrue(first.bottom >= next.top - 0.02f, "following line overlaps the lowered formula")
        val scaled = measured.images.last()
        val beforeWide = measured.text.single { it.value.contains("宽式起点") }
        val tableText = measured.text.single { it.value.contains("宽式下一行") }
        assertTrue(scaled.width < 250f)
        assertEquals(700f / 84f, scaled.width / scaled.height, 0.03f)
        assertEquals(scaled.page, beforeWide.page)
        assertEquals(-23f * scaled.height / 84f, scaled.bottom - beforeWide.baseline, 0.03f)
        assertTrue(tableText.page > scaled.page || (tableText.page == scaled.page && scaled.bottom >= tableText.top - 0.02f))
        assertTrue(measured.text.any { it.value.contains("相邻单元格") })
        assertTrue(measured.text.any { it.value.contains("最终正文") })
    }

    private data class TextBox(val value: String, val page: Int, val baseline: Float, val top: Float)
    private data class ImageBox(val page: Int, val bottom: Float, val height: Float, val width: Float)
    private data class Geometry(val text: List<TextBox>, val images: List<ImageBox>, val pages: Int)

    private fun inspect(file: File): Geometry = PdfDocument(PdfReader(file)).use { pdf ->
        val text = mutableListOf<TextBox>()
        val images = mutableListOf<ImageBox>()
        for (page in 1..pdf.numberOfPages) {
            val parser = PdfCanvasProcessor(object : IEventListener {
                override fun getSupportedEvents() = setOf(EventType.RENDER_TEXT, EventType.RENDER_IMAGE)

                override fun eventOccurred(data: IEventData, type: EventType) {
                    if (data is TextRenderInfo) {
                        text += TextBox(data.text, page, data.baseline.startPoint.get(Vector.I2), data.ascentLine.startPoint.get(Vector.I2))
                    } else if (data is ImageRenderInfo) {
                        val matrix = data.imageCtm
                        images += ImageBox(page, matrix.get(Matrix.I32), matrix.get(Matrix.I22), matrix.get(Matrix.I11))
                    }
                }
            })
            parser.registerXObjectDoHandler(PdfName.Form) { processor, _, stream, _ ->
                val matrix = processor.graphicsState.ctm
                val box = stream.getAsArray(PdfName.BBox).toRectangle()
                images += ImageBox(
                    page, matrix.get(Matrix.I32) + box.bottom * matrix.get(Matrix.I22),
                    box.height * matrix.get(Matrix.I22), box.width * matrix.get(Matrix.I11),
                )
            }
            parser.processPageContent(pdf.getPage(page))
        }
        Geometry(text, images, pdf.numberOfPages)
    }

    private fun svgUri(svg: String) = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray())

    private fun pngUri(): String {
        val bytes = ByteArrayOutputStream()
        ImageIO.write(capturedFormula(18, 18, 18f, 0f).image, "PNG", bytes)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun capturedFormula(width: Int, height: Int, contentHeight: Float, bottomInset: Float): CapturedPdfAsset {
        val image = BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { canvas ->
            try {
                canvas.drawLine(8, image.height / 2, image.width - 9, image.height / 2)
                canvas.drawLine(image.width / 4, 8, image.width / 3, image.height - 9)
            } finally {
                canvas.dispose()
            }
        }
        return CapturedPdfAsset(image, width, height, contentHeight, bottomInset)
    }

    private fun question(html: String): Question {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(html.toByteArray()) }
        return Question("1", "主观题", Base64.getEncoder().encodeToString(buffer.toByteArray()), "", "样例答文", JsonArray(emptyList()))
    }
}
