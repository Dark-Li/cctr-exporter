package top.kagg886.cctr.driver

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.max

data class PdfAssetCapturePlan(val width: Int, val height: Int, val cssScale: Double)

data class PdfAssetCaptureBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Preserve the original layout anchor while extending all sides to include visible overflow. */
fun pdfAssetCaptureBounds(rawWidth: Double, rawHeight: Double, contentLeft: Double, contentTop: Double,
    contentWidth: Double, contentHeight: Double, rootLeft: Double, rootTop: Double,
    rootRight: Double, rootBottom: Double): PdfAssetCaptureBounds = PdfAssetCaptureBounds(
    min(0.0, min(rootLeft, contentLeft - 4.0)), min(0.0, min(rootTop, contentTop - 4.0)),
    max(rawWidth, max(rootRight, contentLeft + contentWidth + 4.0)),
    max(rawHeight, max(rootBottom, contentTop + contentHeight + 4.0)),
)

/** Pure geometry guard, before allocating any screenshot pixels. No source content is reported. */
fun planPdfAssetCapture(
    width: Double, height: Double, contentWidth: Double, contentHeight: Double,
    dpr: Double, maxCssWidth: Double = 650.0, kind: String = "asset", stable: Boolean = true,
): PdfAssetCapturePlan {
    val safeKind = kind.takeIf { it in setOf("svg", "math", "img", "text-font", "asset") } ?: "asset"
    fun fail(reason: String): Nothing = throw IllegalArgumentException(
        "局部内容几何失败；原因=$reason；类型=$safeKind；CSS=${width}x$height；内容=${contentWidth}x$contentHeight；DPR=$dpr",
    )
    if (listOf(width, height, contentWidth, contentHeight, dpr, maxCssWidth).any { !it.isFinite() }) fail("nonfinite")
    if (dpr <= 0 || maxCssWidth <= 0) fail("invalid-scale")
    if (!stable) fail("unstable")
    if (contentWidth <= 0 || contentHeight <= 0 || width <= 0 || height <= 0) fail("zero-content")
    // Limit pathological DOM geometry as well as physical pixel allocation.
    if (width > 1_000_000 || height > 1_000_000) fail("source-dimension-limit")
    val factor = min(1.0, (maxCssWidth + 8.0) / width)
    val outputWidth = ceil(width * factor).toInt()
    val outputHeight = ceil(height * factor).toInt()
    if (outputWidth !in 1..4000 || outputHeight !in 1..10000) fail("dimension-limit")
    if (outputWidth.toLong() * outputHeight > 8_000_000L) fail("pixel-budget")
    return PdfAssetCapturePlan(outputWidth, outputHeight, factor)
}
