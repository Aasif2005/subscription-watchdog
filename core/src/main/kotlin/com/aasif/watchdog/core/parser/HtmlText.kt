package com.aasif.watchdog.core.parser

/**
 * Flattens an HTML e-mail body into the kind of short plain text the SMS rules expect.
 *
 * Receipts write amounts as entities far more often than as literal characters (`&#8377;199`,
 * `&#x20B9;199`), so decoding them is what lets [Extractors] find a currency symbol at all.
 */
object HtmlText {
    private val comment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val scriptOrStyle = Regex(
        """<(script|style)\b[^>]*>.*?</\1\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tag = Regex("""<[^>]*>""")
    private val numericEntity = Regex("""&#(x?)([0-9a-fA-F]+);""")
    private val namedEntity = Regex("""&(amp|lt|gt|quot|apos|nbsp);""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""[\s ]+""")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    )

    fun toPlainText(html: String): String {
        val stripped = html
            .replace(comment, " ")
            .replace(scriptOrStyle, " ")
            .replace(tag, " ")
        // Entities are decoded only after tags are gone, so a decoded "<" cannot look like markup.
        return stripped
            .replace(numericEntity) { m ->
                val radix = if (m.groupValues[1].isEmpty()) 10 else 16
                val code = m.groupValues[2].toIntOrNull(radix)
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else " "
            }
            .replace(namedEntity) { m -> named[m.groupValues[1].lowercase()] ?: " " }
            .replace(whitespace, " ")
            .trim()
    }
}
