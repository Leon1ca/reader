package com.localreader.app.core

/**
 * Whether the book's own layout is used must change layout only, never the reader's font choice. Font families the book names
 * from the system (Songti SC, Noto Serif CJK SC, ...) and generic ones (serif, sans-serif) are therefore redirected to the
 * reader's chosen body font (`--reader-font`, defined by the compiled rules), as Readest ends up doing on a device that lacks
 * those system fonts. Only two things survive: fonts the book embeds itself (its own `@font-face` families) and monospace.
 *
 * Only real style declarations are rewritten: CSS text, and the value of a `style` attribute inside a start tag. Text that merely
 * looks like one (a code sample in the body) is never touched, so canonical text, lengths and locators stay exact.
 */
object EpubFontFamilies {
    const val VARIABLE = "--reader-font"
    private val declaration = Regex("(font-family\\s*:\\s*)([^;}{]*)",RegexOption.IGNORE_CASE)
    private val fontFace = Regex("@font-face\\s*\\{[^}]*\\}",RegexOption.IGNORE_CASE)
    private val declaredFamily = Regex("@font-face\\s*\\{[^}]*?font-family\\s*:\\s*([^;}]+)",RegexOption.IGNORE_CASE)
    /** A `style="..."` attribute that belongs to a start tag: `<tag ... style="...">`; text nodes cannot contain a raw `<` or `>`. */
    private val styleAttribute = Regex("(<[A-Za-z][^<>]*?\\sstyle=\")([^\"<>]*)(\")",RegexOption.IGNORE_CASE)
    private val importantTail = Regex("\\s*!\\s*important\\s*$",RegexOption.IGNORE_CASE)

    private fun name(token: String) = token.trim().trim('"','\'').trim().lowercase()

    /** The families a stylesheet declares with `@font-face` (lower case), i.e. the fonts the book brings along. */
    fun embeddedFamilies(css: String): Set<String> = declaredFamily.findAll(css).map { name(it.groupValues[1]) }.filter { it.isNotEmpty() }.toSet()

    private fun redirectList(list: String,keep: Set<String>): String {
        val important = importantTail.find(list)?.value.orEmpty()
        val tokens = list.removeSuffix(important).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if(tokens.isEmpty() || tokens.any { it.startsWith("var(") || it.lowercase() in setOf("inherit","initial","unset","revert") }) return list
        val out = ArrayList<String>()
        for(token in tokens) {
            val n = name(token)
            val replacement = if(n == "monospace" || n in keep) token else "var($VARIABLE)"
            if(replacement.startsWith("var(") && out.lastOrNull()?.startsWith("var(") == true) continue
            out += replacement
        }
        return out.joinToString(", ") + important
    }
    private fun redirectDeclarations(css: String,keep: Set<String>) =
        declaration.replace(css) { it.groupValues[1] + redirectList(it.groupValues[2],keep) }

    /** For stylesheet text. `@font-face` blocks are left exactly as they are. */
    fun redirectCss(css: String,keep: Set<String> = embeddedFamilies(css)): String {
        if(!css.contains("font-family",ignoreCase = true)) return css
        val out = StringBuilder();var at = 0
        for(block in fontFace.findAll(css)) {
            out.append(redirectDeclarations(css.substring(at,block.range.first),keep)).append(block.value);at = block.range.last + 1
        }
        return out.append(redirectDeclarations(css.substring(at),keep)).toString()
    }
    /** For section HTML: only the `style` attribute of a start tag is touched. */
    fun redirectHtml(html: String,keep: Set<String> = emptySet()): String {
        if(!html.contains("font-family",ignoreCase = true)) return html
        return styleAttribute.replace(html) { it.groupValues[1] + redirectDeclarations(it.groupValues[2],keep) + it.groupValues[3] }
    }
}
