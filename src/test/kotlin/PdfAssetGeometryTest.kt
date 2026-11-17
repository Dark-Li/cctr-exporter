import top.kagg886.cctr.driver.planPdfAssetCapture
import top.kagg886.cctr.driver.pdfAssetCaptureBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfAssetGeometryTest {
    @Test
    fun preservesLayoutAnchorAndExtendsNegativeOriginsBeforeApplyingBudgets() {
        val observed = pdfAssetCaptureBounds(31.40625, 75.796875, -0.59375, 27.796875, 28.0, 44.0, 0.0, 0.0, 8.0, 8.0)
        assertEquals(-4.59375, observed.left)
        assertEquals(0.0, observed.top)
        assertEquals(36.0, observed.width)
        val plan = planPdfAssetCapture(observed.width, observed.height, 28.0, 44.0, 1.25)
        assertEquals(36, plan.width)
        assertEquals(76, plan.height)
        val both = pdfAssetCaptureBounds(8.0, 8.0, -40.0, -30.0, 20.0, 10.0, 0.0, 0.0, 8.0, 8.0)
        assertEquals(-44.0, both.left)
        assertEquals(-34.0, both.top)
        assertEquals(52.0, both.width)
        assertEquals(42.0, both.height)
        val excessive = pdfAssetCaptureBounds(8.0, 8.0, -10.0, -10001.0, 10.0, 10.0, 0.0, 0.0, 8.0, 8.0)
        assertFailsWith<IllegalArgumentException> { planPdfAssetCapture(excessive.width, excessive.height, 10.0, 10.0, 1.0) }
    }

    @Test
    fun acceptsPositiveTinyGeometryAndClassifiesEmptyAndNonfiniteValues() {
        val tiny = planPdfAssetCapture(8.1, 8.1, 0.1, 0.1, 1.25)
        assertEquals(9, tiny.width)
        assertEquals(9, tiny.height)
        for ((width, content, reason) in listOf(Triple(8.0, 0.0, "zero-content"), Triple(Double.NaN, 1.0, "nonfinite"))) {
            val error = assertFailsWith<IllegalArgumentException> { planPdfAssetCapture(width, 8.0, content, content, 1.0) }
            assertTrue(error.message.orEmpty().contains("原因=$reason"), error.message)
        }
    }

    @Test
    fun fitsWideContentWithoutCroppingButRejectsUnsafeTallAndUnstableContent() {
        val wide = planPdfAssetCapture(4108.0, 28.0, 4100.0, 20.0, 2.0, 500.0, "svg")
        assertEquals(508, wide.width)
        assertEquals(4, wide.height)
        assertTrue(wide.cssScale in 0.0..1.0)
        for ((width, height, stable, reason) in listOf(
            Geometry(28.0, 11008.0, true, "dimension-limit"),
            Geometry(28.0, 30.0, false, "unstable"),
            Geometry(1_000_001.0, 30.0, true, "source-dimension-limit"),
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                planPdfAssetCapture(width, height, width - 8, height - 8, 1.0, kind = "svg", stable = stable)
            }
            assertTrue(error.message.orEmpty().contains("原因=$reason"), error.message)
            assertTrue(error.message.orEmpty().contains("类型=svg"), error.message)
        }
        val budget = assertFailsWith<IllegalArgumentException> {
            planPdfAssetCapture(3508.0, 3008.0, 3500.0, 3000.0, 1.0, maxCssWidth = 3500.0)
        }
        assertTrue(budget.message.orEmpty().contains("pixel-budget"), budget.message)
    }

    private data class Geometry(val width: Double, val height: Double, val stable: Boolean, val reason: String)
}
