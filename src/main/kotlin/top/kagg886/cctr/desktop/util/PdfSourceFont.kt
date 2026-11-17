package top.kagg886.cctr.desktop.util

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only explicit source identities may give a private-use character its meaning. */
internal data class PdfSourceFont(val family: String?, val origin: String, val custom: Boolean = false) {
    fun nativeTimesBytes(bold: Boolean, italic: Boolean, stage: String): ByteArray? {
        if (!family.equals("Times New Roman", true)) return null
        if (custom) throw error(0, stage, "自定义字体尚未验证")
        val suffix = when { bold && italic -> "bi"; bold -> "bd"; italic -> "i"; else -> "" }
        val windows = System.getenv("WINDIR") ?: throw error(0, stage, "系统源字体不可用")
        val path = File(windows).resolve("Fonts/times$suffix.ttf")
        if (!path.isFile) throw error(0, stage, "系统源字体不可用")
        val bytes = path.readBytes()
        val exact = Font.createFont(Font.TRUETYPE_FONT, bytes.inputStream())
        if (!exact.family.equals("Times New Roman", true)) throw error(0, stage, "字体文件身份不符")
        // OS/2.fsType: honor restricted/bitmap-only embedding and no-subsetting permissions.
        val table = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = table.getShort(4).toInt() and 0xffff
        val os2 = (0 until count).map { 12 + 16 * it }.firstOrNull { table.getInt(it) == 0x4F532F32 }
            ?: throw error(0, stage, "字体嵌入权限不可验证")
        val fsType = table.getShort(table.getInt(os2 + 8) + 8).toInt() and 0xffff
        if (fsType and 0x0302 != 0) throw error(0, stage, "字体不允许当前嵌入方式")
        return bytes
    }

    fun mapSymbol(point: Int): Int = if (family.equals("Symbol", true) && !custom) {
        // Adobe Symbol encoding, verified against Unicode's VENDORS/ADOBE/symbol.txt.
        when (point) { 0xF070 -> 0x03C0; 0xF0BB -> 0x2248; else -> point }
    } else point

    fun error(point: Int, stage: String, reason: String): IllegalArgumentException {
        val name = family?.replace(Regex("[\\p{Cntrl}<>]"), "")?.take(64) ?: "未声明"
        return IllegalArgumentException(
            "PDF 字体不支持 U+${point.toString(16).uppercase()}；阶段=$stage；源字体=$name；来源=$origin；$reason",
        )
    }

    fun verifiedBrowserFamily(points: IntArray, stage: String): String {
        val point = points.first()
        if (custom) throw error(point, stage, "自定义字体尚未验证")
        val filename = when (family?.lowercase()) {
            "symbol" -> "symbol.ttf"
            "wingdings" -> "wingding.ttf"
            else -> throw error(point, stage, "需要核实源字体及字形")
        }
        val windows = System.getenv("WINDIR") ?: throw error(point, stage, "系统源字体不可用")
        val path = File(windows).resolve("Fonts").resolve(filename)
        if (!path.isFile) throw error(point, stage, "系统源字体不可用")
        val exactFont = path.inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
        if (!exactFont.family.equals(family, true)) throw error(point, stage, "字体文件身份不符")
        for (code in points) {
            val characters = String(Character.toChars(code))
            val glyphs = exactFont.createGlyphVector(FontRenderContext(null, true, true), characters)
            if (!exactFont.canDisplay(code) || glyphs.getGlyphCode(0) == exactFont.missingGlyphCode) {
                throw error(code, stage, "源字体没有该字形")
            }
        }
        return exactFont.family
    }

    companion object {
        fun from(node: TextNode): PdfSourceFont {
            val styles = node.ownerDocument()?.select("style")?.joinToString("\n") { it.data() }.orEmpty()
            val plainStyles = styles.replace(fontFaceRule, "")
            if (plainStyles.contains('@') && plainStyles.contains("font", true)) {
                return PdfSourceFont(null, "未解析条件字体规则")
            }
            if (cssRule.findAll(plainStyles).any { rule ->
                    fontDeclaration.containsMatchIn(rule.groupValues[2]) &&
                        rule.groupValues[1].split(',').any { !simpleSelector.matches(it.trim()) }
                }) return PdfSourceFont(null, "未解析复杂字体选择器")
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
                val family = firstFamily.find(selected.first.trim())?.let {
                    it.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.trim()
                } ?: continue
                if (family.equals("inherit", true) || family.equals("unset", true)) continue
                val custom = fontFaceRule.findAll(styles).any { face ->
                    fontFamilyDeclaration(face.groupValues[1])?.value?.trim()?.trim('\'', '"')?.equals(family, true) == true
                }
                return PdfSourceFont(family, selected.second, custom)
            }
            return PdfSourceFont(null, "未声明")
        }
    }
}

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
private val firstFamily = Regex("""^\s*(?:"([^"]+)"|'([^']+)'|([^,]+))""")
private val fontFaceRule = Regex("@font-face\\s*\\{([^{}]*)}", RegexOption.IGNORE_CASE)
private val cssRule = Regex("([^{}]+)\\{([^{}]*)}")
private val simpleSelector = Regex("[A-Za-z0-9_.*# >+~\\-]+")
