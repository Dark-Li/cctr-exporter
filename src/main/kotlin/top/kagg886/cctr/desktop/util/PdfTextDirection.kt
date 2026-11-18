package top.kagg886.cctr.desktop.util

/** The current renderer supports LTR paragraphs, not contextual RTL shaping/bidi layout. */
internal fun String.pdfLtrText(stage: String): String {
    val output = StringBuilder()
    codePoints().forEach { point ->
        // LRM has no visible glyph. Its strong-L effect is redundant only in this verified LTR scope.
        if (point == 0x200E) return@forEach
        val bidi = Character.getDirectionality(point)
        if (isPdfBidiControl(point) || bidi == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            bidi == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        ) {
            throw PdfContentNotSupportedException(
                "PDF 暂不支持双向文本；阶段=$stage；码点=U+${point.toString(16).uppercase()}",
            )
        }
        output.appendCodePoint(point)
    }
    return output.toString()
}

/**
 * Invisible controls are classified by Unicode category, not by a growing allowlist.
 * Every format character that does not reorder text is drawn as zero width and kept in
 * ActualText. Bidi controls are the only format characters that change layout semantics.
 */
internal fun isPdfZeroWidthControl(point: Int): Boolean =
    Character.getType(point) == Character.FORMAT.toInt() && !isPdfBidiControl(point)

internal fun isPdfBidiControl(point: Int): Boolean =
    point == 0x061C || point == 0x200E || point == 0x200F ||
        point in 0x202A..0x202E || point in 0x2066..0x2069

internal fun String.validatePdfJoiningContext(stage: String) {
    if (codePoints().noneMatch(::isPdfZeroWidthControl)) return
    codePoints().forEach { point ->
        val script = Character.UnicodeScript.of(point)
        if ((script !in safeLtrScripts && Character.getType(point) != Character.PRIVATE_USE.toInt()) ||
            point in 0x1F000..0x1FAFF || point in 0x2600..0x27BF || point in 0xFE00..0xFE0F
        ) {
            throw PdfContentNotSupportedException(
                "PDF 暂不支持此连接排版语境；阶段=$stage；码点=U+${point.toString(16).uppercase()}；脚本=$script",
            )
        }
    }
}

private val safeLtrScripts = setOf(Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED,
    Character.UnicodeScript.LATIN, Character.UnicodeScript.HAN, Character.UnicodeScript.GREEK,
    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
