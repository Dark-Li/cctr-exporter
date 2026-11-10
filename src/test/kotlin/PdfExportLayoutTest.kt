import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import com.itextpdf.kernel.pdf.canvas.parser.data.ClippingPathInfo
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.ImageRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.convertToPDF
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class PdfExportLayoutTest {
    private fun directory(): File = File("build/pdf-validation/tests").apply { mkdirs() }
        .let { Files.createTempDirectory(it.toPath(), "layout-").toFile() }

    private fun picture(dir: File, name: String, width: Int = 780, height: Int = 220, transparent: Boolean = false): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            if (!transparent) { color = Color.WHITE; fillRect(0, 0, width, height) }
            color = Color.BLACK
            for (y in 20 until height - 50 step 40) fillRect(20, y, width - 40, 12)
            dispose()
        }
        return dir.resolve(name).also { ImageIO.write(image, "PNG", it) }
    }

    @Test
    fun exportsNumberedNaturalOrderAndNavigableA4Pages() {
        val dir = directory()
        val ten = picture(dir, "10.png")
        val two = picture(dir, "2.png")
        val pdf = dir.resolve("题库.pdf")
        assertTrue(listOf(ten, two).convertToPDF(pdf))
        PdfDocument(PdfReader(pdf)).use { document ->
            val text = (1..document.numberOfPages).joinToString("\n") { PdfTextExtractor.getTextFromPage(document.getPage(it)) }
            assertTrue(text.contains("第 1 题"), "Questions must have readable numbers")
            assertTrue(text.indexOf("ID 2") in 0 until text.indexOf("ID 10"), "Numeric IDs must be naturally ordered")
            assertEquals(2, document.getOutlines(false).allChildren.size)
            assertTrue(document.getOutlines(false).allChildren.first().title.contains("ID 2"))
            for (page in 1..document.numberOfPages) {
                assertEquals(PageSize.A4.width, document.getPage(page).pageSize.width, 0.1f)
                assertEquals(PageSize.A4.height, document.getPage(page).pageSize.height, 0.1f)
                val footer = PdfTextExtractor.getTextFromPage(document.getPage(page)).lineSequence().last().filterNot(Char::isWhitespace)
                assertTrue(footer.endsWith("$page/${document.numberOfPages}"), "Expected current page and total in the footer")
            }
        }
    }

    @Test
    fun longQuestionKeepsItsWidthAndContinuesAcrossPages() {
        val dir = directory()
        val long = picture(dir, "1.png", height = 3500, transparent = true)
        val pdf = dir.resolve("long.pdf")
        assertTrue(listOf(long).convertToPDF(pdf))
        PdfDocument(PdfReader(pdf)).use { document ->
            assertTrue(document.numberOfPages >= 3, "Long questions must continue instead of shrinking to one page")
            assertTrue(PdfTextExtractor.getTextFromPage(document.getPage(2)).contains("续"))
            assertEquals(1, document.getOutlines(false).allChildren.size)
        }
    }

    @Test
    fun emptyInputFailsWithoutLeavingPdf() {
        val output = directory().resolve("empty.pdf")
        assertFalse(emptyList<File>().convertToPDF(output))
        assertFalse(output.exists())
    }

    @Test
    fun brokenImageDoesNotLeavePartialPdfOrOverwritePreviousExport() {
        val dir = directory()
        val valid = picture(dir, "1.png")
        val broken = dir.resolve("2.png").apply { writeText("not an image") }
        val output = dir.resolve("broken.pdf")
        assertFalse(listOf(valid, broken).convertToPDF(output))
        assertFalse(output.exists(), "Failed conversion must not leave a plausible partial PDF")
        output.writeText("previous valid export")
        assertFalse(listOf(valid, broken).convertToPDF(output))
        assertEquals("previous valid export", output.readText())
    }

    @Test
    fun continuousLongImagePreservesEverySourceRowAtTheSameScale() {
        val dir = directory()
        val source = BufferedImage(780, 3300, BufferedImage.TYPE_INT_RGB)
        source.createGraphics().apply { color = Color(51, 92, 125); fillRect(0, 0, 780, 3300); dispose() }
        val input = dir.resolve("1.png").also { ImageIO.write(source, "PNG", it) }
        val output = dir.resolve("continuous.pdf")
        assertTrue(listOf(input).convertToPDF(output))
        val slices = mutableListOf<Pair<Double, Double>>()
        PdfDocument(PdfReader(output)).use { document ->
            for (page in 1..document.numberOfPages) {
                var clipBottom = Double.NaN
                var clipTop = Double.NaN
                val processor = PdfCanvasProcessor(object : IEventListener {
                    override fun getSupportedEvents() = setOf(EventType.RENDER_IMAGE, EventType.CLIP_PATH_CHANGED)
                    override fun eventOccurred(data: IEventData, type: EventType) {
                        if (type == EventType.CLIP_PATH_CHANGED) {
                            val points = (data as ClippingPathInfo).clippingPath.subpaths.flatMap { it.piecewiseLinearApproximation }
                            clipBottom = points.minOf { it.y }
                            clipTop = points.maxOf { it.y }
                            return
                        }
                        val image = data as ImageRenderInfo
                        val matrix = image.imageCtm
                        val scale = matrix.get(0).toDouble() / 780.0
                        val base = matrix.get(7).toDouble()
                        val start = 3300 - (clipTop - base) / scale
                        val end = 3300 - (clipBottom - base) / scale
                        assertEquals((PageSize.A4.width - 84f).toDouble(), matrix.get(0).toDouble(), 0.02)
                        slices += start to end
                    }
                })
                processor.processPageContent(document.getPage(page))
            }
            assertTrue(slices.size >= 3)
            assertEquals(0.0, slices.first().first, 0.05)
            assertEquals(3300.0, slices.last().second, 0.05)
            slices.zipWithNext().forEach { (a, b) -> assertEquals(a.second, b.first, 0.05, "No missing or repeated source rows") }
        }
    }

    @Test
    fun longChineseMetadataUsesEmbeddedFontAndBookmarksTargetQuestionPages() {
        val dir = directory()
        val inputs = listOf(picture(dir, "10.png", height = 800), picture(dir, "2.png", height = 800))
        val output = dir.resolve("metadata.pdf")
        val title = "中国现代教育课程资料".repeat(12)
        assertTrue(inputs.convertToPDF(output, PdfExportDetails(title, "数据库原理与系统设计".repeat(10), "综合题")))
        PdfDocument(PdfReader(output)).use { document ->
            val text = PdfTextExtractor.getTextFromPage(document.getPage(1))
            assertTrue(text.contains("中国现代教育课程资料"))
            assertTrue(text.contains("…"), "Long metadata must be bounded")
            val fonts = document.getPage(1).resources.getResource(PdfName.Font)
            assertTrue(fonts.keySet().isNotEmpty())
            for (name in fonts.keySet()) {
                val font = fonts.getAsDictionary(name).getAsArray(PdfName.DescendantFonts).getAsDictionary(0)
                val descriptor = font.getAsDictionary(PdfName.FontDescriptor)
                assertNotNull(descriptor.getAsStream(PdfName.FontFile3), "Chinese font must be embedded")
            }
            for ((index, bookmark) in document.getOutlines(false).allChildren.withIndex()) {
                val destination = bookmark.destination.pdfObject as com.itextpdf.kernel.pdf.PdfArray
                val target = destination.getAsDictionary(0)
                val targetPage = (1..document.numberOfPages).first { document.getPage(it).pdfObject == target }
                assertTrue(PdfTextExtractor.getTextFromPage(document.getPage(targetPage)).contains("第 ${index + 1} 题"))
            }
        }
    }
}
