package top.kagg886.cctr.desktop.util

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.util.Locale

/**
 * Installed font faces indexed by their English/localized names.
 *
 * Source-font capture only needs to verify that a declared family exists and can draw a code
 * point; Edge later confirms which family it actually selected. Java's font enumeration covers
 * TTF and TTC faces, so SimSun/NSimSun need no special file parsing.
 */
internal object PdfInstalledFonts {
    private val renderContext = FontRenderContext(null, true, true)

    private val index: Map<String, Font> by lazy {
        val fonts = mutableMapOf<String, Font>()
        GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.forEach { font ->
            sequenceOf(
                font.getFontName(Locale.ENGLISH),
                font.getFamily(Locale.ENGLISH),
                font.getFontName(),
                font.getFamily(),
            ).filter(String::isNotBlank).forEach { name ->
                fonts.putIfAbsent(name.trim().lowercase(Locale.ROOT), font)
            }
        }
        fonts
    }

    fun resolve(family: String): Font? = index[family.trim().lowercase(Locale.ROOT)]

    fun hasGlyph(font: Font, point: Int): Boolean {
        if (!font.canDisplay(point)) return false
        val glyph = font.createGlyphVector(renderContext, String(Character.toChars(point))).getGlyphCode(0)
        return glyph != font.missingGlyphCode
    }
}
