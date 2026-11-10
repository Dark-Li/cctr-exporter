package top.kagg886.cctr.desktop.util

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.ListItem
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.layout.LayoutResult
import com.itextpdf.layout.properties.InlineVerticalAlignment
import com.itextpdf.layout.properties.InlineVerticalAlignmentType
import com.itextpdf.layout.properties.ListNumberingType
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.properties.RenderingMode
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.renderer.IRenderer
import com.itextpdf.layout.renderer.ImageRenderer
import com.itextpdf.svg.converter.SvgConverter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import top.kagg886.cctr.driver.CapturedPdfAsset
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import javax.imageio.ImageIO

/** Preserves DOM reading order while laying out normal content as PDF text. */
internal class PdfHtmlContent(
    private val pdf: PdfDocument,
    private val font: PdfFont,
    private val width: Float,
    private val renderComplex: suspend (String) -> CapturedPdfAsset,
) {
    private val checkedGlyphs = mutableSetOf<Int>()

    fun pdfText(value: String): Text {
        value.codePoints().forEach { point ->
            if (!Character.isISOControl(point) && checkedGlyphs.add(point)) {
                require(font.containsGlyph(point)) { "PDF 字体不支持 U+${point.toString(16).uppercase()}" }
                val glyph = font.fontProgram.getGlyphByCode(font.getGlyph(point).code)
                // The bundled font shares some Han glyphs with radicals. Keep the ordinary Han
                // character in this document's ToUnicode map, independent of other exports.
                if (glyph != null && point in 0x3400..0x9FFF && glyph.unicode != point) {
                    glyph.setChars(Character.toChars(point))
                }
            }
        }
        return Text(value).setFont(font)
    }

    suspend fun addHtml(html: String, emit: (IBlockElement) -> Unit) =
        addNodes(Jsoup.parseBodyFragment(html).body().childNodes(), emit)

    suspend fun addNodes(nodes: List<Node>, emit: (IBlockElement) -> Unit, label: String = "", availableWidth: Float = width) {
        var paragraph = paragraph()
        var hasContent = false
        var pendingLabel = label
        var previousSpace = false
        var lineHasText = false
        var hasAlignedFormula = false
        val lineFormulas = mutableListOf<InlinePdfImage>()

        fun start() {
            if (pendingLabel.isNotEmpty()) {
                paragraph.add(pdfText(pendingLabel).setBold())
                pendingLabel = ""
                previousSpace = true
                lineHasText = true
            }
            hasContent = true
        }

        fun finishLine() {
            if (lineHasText && lineFormulas.isNotEmpty()) {
                lineFormulas.forEach { (image, baselineFraction) ->
                    image.setNextRenderer(FormulaImageRenderer(image, baselineFraction))
                }
                hasAlignedFormula = true
            }
            lineFormulas.clear()
            lineHasText = false
        }

        fun flush() {
            finishLine()
            if (hasAlignedFormula) paragraph.setProperty(Property.RENDERING_MODE, RenderingMode.HTML_MODE)
            if (hasContent) emit(paragraph)
            paragraph = paragraph()
            hasContent = false
            previousSpace = false
            hasAlignedFormula = false
        }

        suspend fun visit(node: Node, format: InlineFormat) {
            when (node) {
                is TextNode -> {
                    var value = node.wholeText
                    if (!format.preformatted) {
                        value = htmlSpaces.replace(value, " ")
                        if (!hasContent || previousSpace) value = value.trimStart(' ')
                    }
                    if (value.isEmpty()) return
                    start()
                    val run = pdfText(value)
                    if (format.bold) run.setBold()
                    if (format.italic) run.setItalic()
                    if (format.underline) run.setUnderline()
                    if (format.rise != 0f) run.setTextRise(format.rise).setFontSize(8.5f)
                    paragraph.add(run)
                    if (format.preformatted && '\n' in value) {
                        lineHasText = lineHasText || value.substringBefore('\n').hasVisiblePdfText()
                        finishLine()
                        lineHasText = value.substringAfterLast('\n').hasVisiblePdfText()
                    } else {
                        lineHasText = lineHasText || value.hasVisiblePdfText()
                    }
                    previousSpace = value.endsWith(' ')
                }
                is Element -> {
                    if (node.isHiddenPdfContent()) return
                    when (val name = node.normalName()) {
                        "br" -> {
                            start()
                            finishLine()
                            paragraph.add(pdfText("\n"))
                            previousSpace = true
                        }
                        "img", "svg", "math" -> {
                            start()
                            val asset = image(node, availableWidth)
                            if (asset.baselineFraction != 0f) lineFormulas.add(asset)
                            paragraph.add(asset.image)
                            previousSpace = false
                        }
                        "table" -> {
                            if (pendingLabel.isNotEmpty()) start()
                            flush()
                            emit(table(node, availableWidth))
                        }
                        "ul", "ol" -> {
                            if (pendingLabel.isNotEmpty()) start()
                            flush()
                            val list = com.itextpdf.layout.element.List()
                            if (name == "ol") {
                                list.setListSymbol(ListNumberingType.DECIMAL)
                                node.attr("start").toIntOrNull()?.let(list::setItemStartIndex)
                            }
                            list.setMarginBottom(4f)
                            for (itemNode in node.children().asSequence().filter { it.normalName() == "li" }) {
                                val item = ListItem()
                                addNodes(itemNode.childNodes(), item::add, availableWidth = (availableWidth - 20f).coerceAtLeast(1f))
                                list.add(item)
                            }
                            emit(list)
                        }
                        "iframe", "object", "embed", "canvas", "video", "audio" -> error("不支持题目中的 <$name> 内容")
                        else -> {
                            val block = name in blockTags
                            if (block) flush()
                            val nextFormat = format.with(node)
                            for (child in node.childNodes()) visit(child, nextFormat)
                            if (block) flush()
                        }
                    }
                }
            }
        }

        for (node in nodes) visit(node, InlineFormat())
        if (pendingLabel.isNotEmpty()) start()
        flush()
    }

    private fun paragraph() = Paragraph().setMultipliedLeading(1.35f).setMarginTop(0f).setMarginBottom(5f)

    private suspend fun table(source: Element, availableWidth: Float): IBlockElement {
        val rows = source.select("tr").asSequence().filter { it.closest("table") === source }.toList()
        require(rows.isNotEmpty()) { "无法读取表格行" }
        val occupied = Array(rows.size) { BooleanArray(64) }
        val placements = Array(rows.size) { mutableMapOf<Int, CellPlacement>() }
        var columns = 1
        rows.forEachIndexed { rowIndex, row ->
            var column = 0
            for (element in row.children().asSequence().filter { it.normalName() == "td" || it.normalName() == "th" }) {
                val rowSpan = (element.attr("rowspan").toIntOrNull() ?: 1).let {
                    if (it == 0) rows.drop(rowIndex).takeWhile { sibling -> sibling.parent() === row.parent() }.size
                    else it.coerceIn(1, rows.size - rowIndex)
                }
                val colSpan = (element.attr("colspan").toIntOrNull() ?: 1).coerceAtLeast(1)
                while (column < 64 && occupied[rowIndex][column]) column++
                require(colSpan <= 64 - column) { "表格列数超过 64" }
                for (r in rowIndex until rowIndex + rowSpan) {
                    for (c in column until column + colSpan) {
                        require(!occupied[r][c]) { "表格单元格跨度相互覆盖" }
                        occupied[r][c] = true
                    }
                }
                placements[rowIndex][column] = CellPlacement(element, rowSpan, colSpan)
                column += colSpan
                columns = maxOf(columns, column)
            }
        }
        val table = Table(UnitValue.createPercentArray(columns)).useAllAvailableWidth().setFixedLayout().setMarginBottom(7f)
        val border = SolidBorder(DeviceRgb(209, 219, 227), 0.5f)
        for (row in rows.indices) {
            for (column in 0 until columns) {
                val placement = placements[row][column]
                if (placement != null) {
                    val cell = Cell(placement.rows, placement.columns).setBorder(border).setPadding(5f)
                    if (placement.element.normalName() == "th") cell.setBackgroundColor(DeviceRgb(240, 245, 248)).setBold()
                    val cellWidth = (availableWidth * placement.columns / columns - 12f).coerceAtLeast(1f)
                    addNodes(placement.element.childNodes(), cell::add, availableWidth = cellWidth)
                    table.addCell(cell)
                } else if (!occupied[row][column]) {
                    table.addCell(Cell().setBorder(border))
                }
            }
        }
        return Div().apply {
            source.children().firstOrNull { it.normalName() == "caption" }?.let {
                addNodes(it.childNodes(), this::add, availableWidth = availableWidth)
            }
            add(table)
        }
    }

    private suspend fun image(element: Element, availableWidth: Float): InlinePdfImage {
        val name = element.normalName()
        val svgSource = when (name) {
            "svg" -> element.outerHtml()
            "img" -> element.attr("src").takeIf { it.startsWith("data:image/svg+xml", ignoreCase = true) }
                ?.let { decodeDataImage(it).toString(Charsets.UTF_8) }
            else -> null
        }
        if (svgSource != null && !element.hasCssSvgSizing() && supportsVectorSvg(svgSource)) {
            val vector = runCatching { Image(SvgConverter.convertToXObject(svgSource, pdf)) }.getOrNull()
            if (vector != null && vector.imageWidth > 0 && vector.imageHeight > 0) {
                val preferredWidth = element.pdfWidth() ?: vector.imageWidth
                val cssHeight = vector.imageHeight * preferredWidth / vector.imageWidth / pointsPerCssPixel
                return element.alignFormula(sizeImage(vector, preferredWidth, availableWidth), svgSource, cssHeight)
            }
        }
        if (name == "img") {
            val source = element.attr("src").trim()
            require(source.isNotEmpty()) { "题目图片缺少地址" }
            if (source.startsWith("data:image/", ignoreCase = true) && svgSource == null) {
                val raster = Image(ImageDataFactory.create(decodeDataImage(source)))
                val preferredWidth = element.pdfWidth() ?: raster.imageWidth * pointsPerCssPixel
                val cssHeight = raster.imageHeight * preferredWidth / raster.imageWidth / pointsPerCssPixel
                return element.alignFormula(sizeImage(raster, preferredWidth, availableWidth), null, cssHeight)
            }
            if (svgSource == null) {
                val url = URI(if (source.startsWith("//")) "https:$source" else source)
                require(url.scheme?.lowercase() in setOf("http", "https") && url.host != null) { "不支持的题目图片地址" }
                element.attr("src", url.toString())
            }
        }
        val rendered = renderComplex(element.outerHtml())
        val bytes = ByteArrayOutputStream().also { check(ImageIO.write(rendered.image, "PNG", it)) }.toByteArray()
        val image = sizeImage(Image(ImageDataFactory.create(bytes)), rendered.cssWidth * pointsPerCssPixel, availableWidth)
        return element.alignFormula(
            image, svgSource, rendered.cssHeight.toFloat(), rendered.contentHeightCssPixels, rendered.bottomInsetCssPixels,
        )
    }

    private fun Element.pdfWidth(): Float? = attr("width").removeSuffix("px").toFloatOrNull()
        ?.takeIf { it > 0 && it.isFinite() }?.times(pointsPerCssPixel)

    private fun Element.alignFormula(
        image: Image,
        svgSource: String?,
        cssHeight: Float,
        contentHeight: Float = cssHeight,
        bottomInset: Float = 0f,
    ): InlinePdfImage {
        val wrapper = parent()?.takeIf { it.normalName() == "span" && it.childrenSize() == 1 && !it.ownText().hasVisiblePdfText() }
        if (attr("display").equals("block", ignoreCase = true) ||
            blockDisplay.containsMatchIn(attr("style")) || blockDisplay.containsMatchIn(wrapper?.attr("style").orEmpty())) {
            return InlinePdfImage(image)
        }
        val declaration = verticalAlignDeclaration() ?: wrapper?.verticalAlignDeclaration()
        val cssOffset = declaration?.cssBaselineOffset()
        val wiris = svgSource?.contains("wrs:baseline") == true
        if (normalName() != "math" && classNames().none { it.equals("Wirisformula", ignoreCase = true) } &&
            !wiris && (svgSource == null || cssOffset == null)) {
            return InlinePdfImage(image)
        }
        val sourceOffset = if (declaration != null) {
            cssOffset?.takeIf { it in -contentHeight..contentHeight } ?: 0f
        } else {
            if (wiris) wirisBaselineFraction(svgSource.orEmpty()) * contentHeight else 0f
        }
        val fraction = (sourceOffset - bottomInset) / cssHeight
        return InlinePdfImage(image, fraction.takeIf(Float::isFinite) ?: 0f)
    }

    private fun sizeImage(image: Image, preferredWidth: Float, availableWidth: Float): Image {
        val originalWidth = image.imageWidth
        val originalHeight = image.imageHeight
        require(originalWidth > 0 && originalHeight > 0) { "图片尺寸无效" }
        val scale = minOf(preferredWidth / originalWidth, availableWidth / originalWidth, 650f / originalHeight)
        return image.scaleAbsolute(originalWidth * scale, originalHeight * scale)
            .setMaxWidth(UnitValue.createPercentValue(100f))
    }

    private fun decodeDataImage(uri: String): ByteArray {
        val comma = uri.indexOf(',')
        require(comma >= 0) { "图片数据 URI 不完整" }
        val payload = uri.substring(comma + 1)
        require(payload.length <= 32_000_000) { "题目图片数据过大" }
        return if (uri.substring(0, comma).endsWith(";base64", ignoreCase = true)) {
            Base64.getDecoder().decode(payload)
        } else {
            ByteArrayOutputStream().apply {
                var start = 0
                while (true) {
                    val encoded = payload.indexOf('%', start)
                    if (encoded < 0) {
                        write(payload.substring(start).toByteArray(Charsets.UTF_8))
                        break
                    }
                    write(payload.substring(start, encoded).toByteArray(Charsets.UTF_8))
                    require(encoded + 2 < payload.length) { "图片数据 URI 的转义不完整" }
                    write(requireNotNull(payload.substring(encoded + 1, encoded + 3).toIntOrNull(16)) { "图片数据 URI 含无效转义" })
                    start = encoded + 3
                }
            }.toByteArray()
        }
    }

    private data class CellPlacement(val element: Element, val rows: Int, val columns: Int)

    private data class InlineFormat(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val rise: Float = 0f,
        val preformatted: Boolean = false,
    ) {
        fun with(element: Element): InlineFormat {
            val tag = element.normalName()
            val style = element.attr("style").lowercase().replace(" ", "")
            return InlineFormat(
                bold || tag in setOf("strong", "b", "h1", "h2", "h3", "h4") || "font-weight:bold" in style,
                italic || tag in setOf("em", "i") || "font-style:italic" in style,
                underline || tag == "u" || "text-decoration:underline" in style,
                when (tag) { "sup" -> 3.5f; "sub" -> -2.5f; else -> rise },
                preformatted || tag == "pre",
            )
        }
    }
}

private data class InlinePdfImage(val image: Image, val baselineFraction: Float = 0f)

private class FormulaImageRenderer(private val image: Image, private val baselineFraction: Float) : ImageRenderer(image) {
    override fun layout(context: LayoutContext): LayoutResult {
        val result = super.layout(context)
        result.occupiedArea?.getBBox()?.height?.let { height ->
            // iText may fit the image again to the remaining line width.
            setProperty(
                Property.INLINE_VERTICAL_ALIGNMENT,
                InlineVerticalAlignment(InlineVerticalAlignmentType.FIXED, baselineFraction * height),
            )
        }
        return result
    }

    override fun getNextRenderer(): IRenderer = FormulaImageRenderer(image, baselineFraction)
}

private fun Element.verticalAlignDeclaration(): String? {
    val values = verticalAlign.findAll(attr("style")).map { it.groupValues[1].trim() }.toList()
    return values.lastOrNull { it.endsWith("!important", ignoreCase = true) } ?: values.lastOrNull()
}

private fun String.cssBaselineOffset(): Float? {
    val value = substringBefore('!').trim().lowercase()
    if (value == "baseline") return 0f
    val match = cssBaselineLength.matchEntire(value) ?: return null
    val amount = match.groupValues[1].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    return when (match.groupValues[2]) {
        "px" -> amount
        "pt" -> amount / pointsPerCssPixel
        else -> amount.takeIf { it == 0f }
    }
}

private fun wirisBaselineFraction(source: String): Float {
    val root = Jsoup.parse(source, "", Parser.xmlParser()).children().singleOrNull() ?: return 0f
    if (root.normalName() != "svg") return 0f
    val height = root.attr("height").trim().removeSuffix("px").toFloatOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return 0f
    val baseline = root.attr("wrs:baseline").toFloatOrNull()?.takeIf { it in 0f..height } ?: return 0f
    return (baseline - height) / height
}

private fun supportsVectorSvg(source: String): Boolean {
    val root = Jsoup.parse(source, "", Parser.xmlParser()).children().singleOrNull() ?: return false
    if (root.normalName() != "svg" || root.select(vectorShapes.joinToString(",")).isEmpty()) return false
    return root.getAllElements().all { node ->
        (node === root || node.normalName() in vectorShapes || node.normalName() in vectorContainers) &&
            node.attributes().all { attribute ->
                val name = attribute.key.lowercase()
                when (name) {
                    in vectorLengths -> svgAbsoluteLength.matches(attribute.value.trim())
                    in vectorAttributes -> '(' !in attribute.value || name == "transform"
                    else -> false
                }
            }
    }
}

private fun Element.hasCssSvgSizing(): Boolean =
    listOf("width", "height").any { hasAttr(it) && !svgAbsoluteLength.matches(attr(it).trim()) } ||
        svgCssSize.containsMatchIn(attr("style"))

internal fun visiblePdfHtmlNodes(markup: String): List<Node>? = Jsoup.parseBodyFragment(markup).body().childNodes()
    .takeIf { nodes -> nodes.any(Node::hasVisiblePdfContent) }

internal fun String.hasVisiblePdfText(): Boolean = codePoints().anyMatch {
    !Character.isWhitespace(it) && !Character.isSpaceChar(it) && !Character.isISOControl(it) &&
        Character.getType(it) != Character.FORMAT.toInt()
}

private fun Node.hasVisiblePdfContent(): Boolean = when (this) {
    is TextNode -> wholeText.hasVisiblePdfText()
    is Element -> !isHiddenPdfContent() && when (normalName()) {
        "img" -> attr("src").isNotBlank()
        "svg", "math" -> children().any { !it.isHiddenPdfContent() } || text().hasVisiblePdfText()
        else -> childNodes().any(Node::hasVisiblePdfContent)
    }
    else -> false
}

private fun Element.isHiddenPdfContent(): Boolean =
    hasAttr("hidden") || normalName() in nonRenderedTags || hiddenStyle.containsMatchIn(attr("style"))

private val htmlSpaces = Regex("[ \\t\\n\\r\\u000c]+")
private val hiddenStyle = Regex("(?i)(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\b")
private val nonRenderedTags = setOf("script", "style", "template", "noscript")
private val blockTags = setOf("p", "div", "section", "article", "blockquote", "pre", "li", "h1", "h2", "h3", "h4", "h5", "h6")
private val vectorShapes = setOf("path", "line", "polyline", "polygon", "rect", "circle", "ellipse")
private val vectorContainers = setOf("g", "title", "desc")
private val vectorLengths = setOf("width", "height", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "stroke-width")
private val vectorAttributes = setOf(
    "xmlns", "viewbox", "preserveaspectratio", "d", "points", "transform", "fill", "fill-rule", "fill-opacity",
    "stroke", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset", "opacity",
)
private val svgAbsoluteLength = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:px|pt)?", RegexOption.IGNORE_CASE)
private val svgCssSize = Regex("(?:^|;)\\s*(?:(?:min|max)-)?(?:width|height)\\s*:", RegexOption.IGNORE_CASE)
private const val pointsPerCssPixel = 0.75f
private val verticalAlign = Regex("(?:^|;)\\s*vertical-align\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
private val cssBaselineLength = Regex("([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))(px|pt)?")
private val blockDisplay = Regex("(?:^|;)\\s*display\\s*:\\s*block\\b", RegexOption.IGNORE_CASE)
