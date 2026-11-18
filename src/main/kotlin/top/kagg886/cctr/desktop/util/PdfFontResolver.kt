package top.kagg886.cctr.desktop.util

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import java.awt.Font
import java.io.File

/** Content the renderer cannot express; only these may be isolated per question. */
internal open class PdfQuestionSkippedException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** A glyph that no verified local font can draw. */
internal class PdfGlyphNotSupportedException(message: String, cause: Throwable? = null) :
    PdfQuestionSkippedException(message, cause)

/** Text the LTR renderer does not support, such as bidi controls or unverified format controls. */
internal class PdfContentNotSupportedException(message: String, cause: Throwable? = null) :
    PdfQuestionSkippedException(message, cause)

internal sealed interface GlyphRoute {
    data class Native(val font: PdfFont) : GlyphRoute

    /** Private-use code point: meaning depends on the declared source font, keep the capture path. */
    data object SourceGlyph : GlyphRoute

    data class Unsupported(val attempts: List<String>) : GlyphRoute
}

/**
 * Resolves one code point to a font without guessing source identities.
 *
 * Order: declared Times New Roman (when the node explicitly uses it), bundled CJK font, then a
 * fixed allowlist of Windows symbol fonts. Private-use code points never take this path.
 */
internal class PdfFontResolver(private val bundled: PdfFont) {
    private val nativeFonts = mutableMapOf<Pair<Boolean, Boolean>, PdfFont>()
    private val fallbackFonts = mutableMapOf<String, LoadedFallback>()

    fun isFallback(font: PdfFont): Boolean = fallbackFonts.values.any { it.font === font }

    fun route(point: Int, source: PdfSourceFont?, stage: String, bold: Boolean, italic: Boolean): GlyphRoute {
        if (isPdfZeroWidthControl(point)) return GlyphRoute.Native(bundled)
        if (Character.isISOControl(point)) return GlyphRoute.Native(bundled)
        if (Character.getType(point) == Character.PRIVATE_USE.toInt()) return GlyphRoute.SourceGlyph
        if (source != null && source.isTimes) {
            if (source.custom) throw source.error(point, stage, "自定义字体尚未验证")
            val times = nativeFonts.getOrPut(bold to italic) {
                PdfFontFactory.createFont(
                    requireNotNull(source.nativeTimesBytes(bold, italic, stage)),
                    PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED,
                    false,
                )
            }
            if (times.containsGlyph(point)) return GlyphRoute.Native(times)
        }
        if (bundled.containsGlyph(point)) return GlyphRoute.Native(bundled)

        val attempts = mutableListOf<String>()
        if (source?.isTimes == true) attempts += "Times New Roman(无字形)"
        attempts += "SourceHanSansCN(无字形)"
        for (candidate in fallbackCandidates) {
            val loaded = fallbackFonts.getOrPut(candidate.fileName) { load(candidate) }
            val font = loaded.font
            if (font == null) {
                attempts += "${candidate.family}(${loaded.failure})"
                continue
            }
            val glyph = runCatching { font.getGlyph(point) }.getOrNull()
            if (glyph == null || glyph.code == 0 || !font.containsGlyph(point)) {
                attempts += "${candidate.family}(无字形)"
                continue
            }
            return GlyphRoute.Native(font)
        }
        return GlyphRoute.Unsupported(attempts)
    }

    private fun load(candidate: FallbackCandidate): LoadedFallback {
        val windows = System.getenv("WINDIR") ?: return LoadedFallback(null, "系统字体目录不可用")
        val file = File(windows).resolve("Fonts").resolve(candidate.fileName)
        if (!file.isFile) return LoadedFallback(null, "文件缺失")
        val bytes = runCatching { file.readBytes() }.getOrElse { return LoadedFallback(null, "读取失败") }
        val exact = runCatching { Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream()) }.getOrNull()
            ?: return LoadedFallback(null, "字体格式不可读")
        if (!exact.family.equals(candidate.family, true)) return LoadedFallback(null, "字体身份不符")
        if (!allowsFontEmbedding(bytes)) return LoadedFallback(null, "不允许嵌入")
        val font = runCatching {
            PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED, false)
                .apply { setSubset(true) }
        }.getOrElse { return LoadedFallback(null, "无法加载") }
        return LoadedFallback(font, null)
    }

    private data class FallbackCandidate(val family: String, val fileName: String)

    private data class LoadedFallback(val font: PdfFont?, val failure: String?)

    private companion object {
        val fallbackCandidates = listOf(
            FallbackCandidate("Segoe UI Symbol", "seguisym.ttf"),
            FallbackCandidate("Arial Unicode MS", "arialuni.ttf"),
        )
    }
}
