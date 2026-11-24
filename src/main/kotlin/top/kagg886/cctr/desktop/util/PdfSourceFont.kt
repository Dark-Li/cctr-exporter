package top.kagg886.cctr.desktop.util

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.awt.Font
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

/** Only explicit source identities may give a private-use character its meaning. */
internal data class PdfSourceFont(val families: List<String>, val origin: String, val custom: Boolean = false) {
    val family: String? get() = families.firstOrNull()
    val isTimes: Boolean get() = family.equals("Times New Roman", true)

    fun nativeTimesBytes(bold: Boolean, italic: Boolean, stage: String): ByteArray? {
        if (!isTimes) return null
        if (custom) throw error(0, stage, "自定义字体尚未验证")
        val suffix = when { bold && italic -> "bi"; bold -> "bd"; italic -> "i"; else -> "" }
        val windows = System.getenv("WINDIR") ?: throw error(0, stage, "系统源字体不可用")
        val path = File(windows).resolve("Fonts/times$suffix.ttf")
        if (!path.isFile) throw error(0, stage, "系统源字体不可用")
        val bytes = path.readBytes()
        val exact = Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream())
        if (!exact.family.equals("Times New Roman", true)) throw error(0, stage, "字体文件身份不符")
        // OS/2.fsType: honor restricted/bitmap-only embedding and no-subsetting permissions.
        val fsType = fontEmbeddingPermission(bytes) ?: throw error(0, stage, "字体嵌入权限不可验证")
        if (fsType and 0x0302 != 0) throw error(0, stage, "字体不允许当前嵌入方式")
        return bytes
    }

    fun mapSymbol(point: Int): Int = if (family.equals("Symbol", true) && !custom) {
        // Adobe Symbol encoding, verified against Unicode's VENDORS/ADOBE/symbol.txt.
        when (point) { 0xF070 -> 0x03C0; 0xF0BB -> 0x2248; else -> point }
    } else point

    fun error(point: Int, stage: String, reason: String): PdfGlyphNotSupportedException {
        val name = family?.replace(Regex("[\\p{Cntrl}<>]"), "")?.take(64) ?: "未声明"
        return PdfGlyphNotSupportedException(
            "PDF 字体不支持 U+${point.toString(16).uppercase()}；阶段=$stage；源字体=$name；字体栈=${stackForMessage()}；来源=$origin；$reason",
        )
    }

    fun verifiedBrowserFamily(points: IntArray, stage: String): String {
        val resolution = resolveBrowserFamily(points, stage)
        return resolution.family ?: throw error(
            points.first(), stage, "源字体没有该字形；候选=${resolution.attempts.joinToString("、")}",
        )
    }

    /**
     * The first installed family may lack a private-use glyph; the browser then falls back to the
     * next declared family. A missing first family stays fatal because its substitution is unknown.
     */
    private fun resolveBrowserFamily(points: IntArray, stage: String): SourceFamilyResolution {
        val point = points.first()
        if (custom) throw error(point, stage, "自定义字体尚未验证")
        val first = families.firstOrNull() ?: throw error(point, stage, "未声明来源字体")
        val firstFont = PdfInstalledFonts.resolve(first)
            ?: throw error(point, stage, "首字体未安装；候选=${families.joinToString("、")}")
        val firstMissing = points.firstOrNull { !PdfInstalledFonts.hasGlyph(firstFont, it) }
        if (firstMissing == null) return SourceFamilyResolution(first, emptyList())

        val attempts = mutableListOf("$first(无字形 U+${firstMissing.toString(16).uppercase()})")
        for (candidate in families.drop(1)) {
            if (candidate.lowercase(Locale.ROOT) in genericFamilies) {
                attempts += "$candidate(通用字体族)"
                continue
            }
            val font = PdfInstalledFonts.resolve(candidate)
            if (font == null) {
                attempts += "$candidate(未安装)"
                continue
            }
            val missing = points.firstOrNull { !PdfInstalledFonts.hasGlyph(font, it) }
            if (missing != null) {
                attempts += "$candidate(无字形 U+${missing.toString(16).uppercase()})"
                continue
            }
            return SourceFamilyResolution(candidate, attempts)
        }
        return SourceFamilyResolution(null, attempts)
    }

    /**
     * Private-use runs with no installed glyph source become U+FFFD. The surrounding text stays
     * native; the warning carries only metadata (stage, code points, font stack).
     */
    fun replaceUnrenderablePrivateUse(value: String, stage: String, onWarning: (String) -> Unit): String {
        if (value.codePoints().noneMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() }) return value
        val points = value.codePoints().toArray()
        val output = StringBuilder(value.length)
        var index = 0
        while (index < points.size) {
            if (Character.getType(points[index]) != Character.PRIVATE_USE.toInt()) {
                output.appendCodePoint(points[index++])
                continue
            }
            var end = index
            while (end < points.size && Character.getType(points[end]) == Character.PRIVATE_USE.toInt()) end++
            val run = points.copyOfRange(index, end)
            val resolution = resolveBrowserFamily(run, stage)
            if (resolution.family != null) {
                run.forEach(output::appendCodePoint)
            } else {
                run.forEach { output.append('\uFFFD') }
                val codes = run.joinToString("、") { "U+${it.toString(16).uppercase()}" }
                onWarning("字符 $codes 无法还原；阶段=$stage；字体栈=${stackForMessage()}；已用 � 替代")
            }
            index = end
        }
        return output.toString()
    }

    private fun stackForMessage(): String = families.joinToString(", ") { it.replace(Regex("[\\p{Cntrl}<>]"), "") }
        .take(200).ifBlank { "未声明" }

    companion object {
        fun from(node: TextNode): PdfSourceFont {
            val styles = node.ownerDocument()?.select("style")?.joinToString("\n") { it.data() }.orEmpty()
            val plainStyles = styles.replace(fontFaceRule, "")
            if (plainStyles.contains('@') && plainStyles.contains("font", true)) {
                return PdfSourceFont(emptyList(), "未解析条件字体规则")
            }
            if (cssRule.findAll(plainStyles).any { rule ->
                    fontDeclaration.containsMatchIn(rule.groupValues[2]) &&
                        rule.groupValues[1].split(',').any { !simpleSelector.matches(it.trim()) }
                }) return PdfSourceFont(emptyList(), "未解析复杂字体选择器")
            for (element in generateSequence(node.parent() as? Element) { it.parent() }) {
                val rule = sourceFontRule(element, styles)
                val inline = fontFamilyDeclaration(element.attr("style"))
                val selected = when {
                    inline?.important == true -> inline.value to "行内CSS"
                    rule?.important == true -> rule.value to "样式规则"
                    inline != null -> inline.value to "行内CSS"
                    rule != null -> rule.value to "样式规则"
                    element.normalName() == "font" && element.attr("face").isNotBlank() -> element.attr("face") to "font-face属性"
                    else -> null
                } ?: continue
                val families = parseFontFamilies(selected.first)
                if (families.isEmpty()) continue
                if (families.first().equals("inherit", true) || families.first().equals("unset", true)) continue
                val custom = fontFaceRule.findAll(styles).any { face ->
                    val faceFamily = fontFamilyDeclaration(face.groupValues[1])?.value?.let(::normalizeFamily)
                        ?: return@any false
                    families.any { it.equals(faceFamily, true) }
                }
                return PdfSourceFont(families, selected.second, custom)
            }
            return PdfSourceFont(emptyList(), "未声明")
        }
    }
}

private data class SourceFamilyResolution(val family: String?, val attempts: List<String>)

/**
 * Font identities come from source CSS and may carry zero-width or non-breaking characters that
 * browsers ignore. Normalize before any equality check; the visible bytes must stay untouched.
 */
internal fun normalizeFamily(raw: String?): String {
    if (raw == null) return ""
    return Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .replace(familyFormatChars, "")
        .replace(familySpaces, " ")
        .trim()
        .trim('\'', '"', '\u2018', '\u2019', '\u201C', '\u201D')
        .trim()
}

private val familyFormatChars = Regex("[\\p{Cf}]")
private val familySpaces = Regex("\\s+")

/** OS/2 fsType, or null when the table cannot be read. */
internal fun fontEmbeddingPermission(bytes: ByteArray): Int? = runCatching {
    if (bytes.size < 12) return@runCatching null
    val table = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val count = table.getShort(4).toInt() and 0xffff
    val os2 = (0 until count).map { 12 + 16 * it }.firstOrNull { table.getInt(it) == 0x4F532F32 }
        ?: return@runCatching null
    table.getShort(table.getInt(os2 + 8) + 8).toInt() and 0xffff
}.getOrNull()

internal fun allowsFontEmbedding(bytes: ByteArray): Boolean =
    fontEmbeddingPermission(bytes)?.let { it and 0x0302 == 0 } == true

private data class FontDeclaration(val value: String, val important: Boolean)

private fun fontFamilyDeclaration(style: String): FontDeclaration? {
    val values = fontDeclaration.findAll(style).map { match ->
        val raw = match.groupValues[2].trim()
        val value = if (match.groupValues[1].equals("font", true)) "未解析font简写" else raw.replace(important, "").trim()
        FontDeclaration(value, important.containsMatchIn(raw))
    }.filter { it.value.isNotBlank() }.toList()
    return values.lastOrNull { it.important } ?: values.lastOrNull()
}

private fun sourceFontRule(element: Element, styles: String): FontDeclaration? {
    var result: FontDeclaration? = null
    var specificity = -1
    // Only simple, unconditional source rules are eligible for a font identity.
    if (styles.replace(fontFaceRule, "").contains('@')) return null
    for (rule in cssRule.findAll(styles.replace(fontFaceRule, ""))) {
        val declaration = fontFamilyDeclaration(rule.groupValues[2]) ?: continue
        for (selector in rule.groupValues[1].split(',')) {
            if (!simpleSelector.matches(selector.trim())) continue
            if (!runCatching { element.`is`(selector.trim()) }.getOrDefault(false)) continue
            val priority = (if (declaration.important) 1_000_000 else 0) +
                selector.count { it == '#' } * 10000 + selector.count { it == '.' || it == '[' } * 100 +
                Regex("(?:^|[ >+~])\\s*[a-zA-Z]").findAll(selector).count()
            if (priority >= specificity) {
                specificity = priority
                result = declaration
            }
        }
    }
    return result
}

private val fontDeclaration = Regex("(?:^|;)\\s*(font-family|font)\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
private val important = Regex("!\\s*important\\s*$", RegexOption.IGNORE_CASE)

private fun parseFontFamilies(value: String): List<String> =
    value.split(',').mapNotNull { part ->
        val raw = part.trim().trim('\'', '"', '\u2018', '\u2019', '\u201C', '\u201D').trim()
        if (raw.isEmpty()) null else normalizeFamily(raw).takeIf(String::isNotEmpty)
    }

private val genericFamilies = setOf(
    "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui",
    "ui-serif", "ui-sans-serif", "ui-monospace", "ui-rounded", "math", "emoji", "fangsong",
)

private val fontFaceRule = Regex("@font-face\\s*\\{([^{}]*)}", RegexOption.IGNORE_CASE)
private val cssRule = Regex("([^{}]+)\\{([^{}]*)}")
private val simpleSelector = Regex("[A-Za-z0-9_.*# >+~\\-]+")
