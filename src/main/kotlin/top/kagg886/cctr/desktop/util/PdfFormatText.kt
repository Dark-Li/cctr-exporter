package top.kagg886.cctr.desktop.util

import com.itextpdf.io.font.otf.GlyphLine
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.renderer.IRenderer
import com.itextpdf.layout.renderer.TextRenderer
import com.itextpdf.layout.splitting.DefaultSplitCharacters
import com.itextpdf.layout.splitting.ISplitCharacters

/** Keep logical zero-width controls on adjacent visible glyphs, never in the font glyph lookup. */
internal fun formatControlText(value: String, font: PdfFont, existing: Text? = null): Text {
    if (value.codePoints().noneMatch { isPdfZeroWidthControl(it) }) return (existing ?: Text(value)).setFont(font)
    val logical = mutableListOf<String>()
    val noBreakAfter = mutableSetOf<Int>()
    var prefix = ""
    value.codePoints().forEach { point ->
        val string = String(Character.toChars(point))
        if (isPdfZeroWidthControl(point)) {
            if (logical.isEmpty()) prefix += string else logical[logical.lastIndex] += string
            if (point == 0x2060 || point == 0xFEFF) noBreakAfter.add(logical.lastIndex)
        } else {
            logical.add(prefix + string)
            prefix = ""
        }
    }
    val drawable = value.codePoints().filter { !isPdfZeroWidthControl(it) }.toArray()
        .joinToString("") { String(Character.toChars(it)) }
    if (drawable.isEmpty()) throw PdfContentNotSupportedException("零宽格式控制没有可附着的可见文字")
    val glyphs = font.createGlyphLine(drawable)
    if (glyphs.size() != logical.size) {
        throw PdfContentNotSupportedException("无法验证局部文字格式控制与字形的对应关系")
    }
    logical.forEachIndexed { index, actual ->
        if (actual.codePoints().anyMatch { isPdfZeroWidthControl(it) }) glyphs.setActualText(index, index + 1, actual)
    }
    val model = (existing ?: Text(drawable)).setFont(font)
    model.setText(drawable)
    if (noBreakAfter.isNotEmpty()) model.setProperty(Property.SPLIT_CHARACTERS, object : ISplitCharacters {
        val ordinary = DefaultSplitCharacters()
        override fun isSplitCharacter(text: GlyphLine, glyphPos: Int): Boolean =
            glyphPos !in noBreakAfter && ordinary.isSplitCharacter(text, glyphPos)
    })
    model.setNextRenderer(FormatTextRenderer(model, glyphs, font))
    return model
}

private class FormatTextRenderer(private val model: Text, private val glyphs: GlyphLine, private val font: PdfFont) : TextRenderer(model) {
    init { setText(glyphs, font) }
    override fun getNextRenderer(): IRenderer = FormatTextRenderer(model, glyphs, font)
}
