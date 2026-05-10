package dev.josu.hypecar.core.model

private val HtmlTagPattern = Regex("<[^>]+>")
private val WhitespacePattern = Regex("\\s+")
private val NumericEntityPattern = Regex("&#(x?[0-9A-Fa-f]+);")

private val NamedEntities = mapOf(
    "&nbsp;" to " ",
    "&amp;" to "&",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&lt;" to "<",
    "&gt;" to ">",
    "&ldquo;" to "\"",
    "&rdquo;" to "\"",
    "&lsquo;" to "'",
    "&rsquo;" to "'",
    "&ndash;" to "-",
    "&mdash;" to "-",
    "&hellip;" to "...",
)

fun String.toDisplayText(): String =
    replace(HtmlTagPattern, " ")
        .decodeBasicHtmlEntities()
        .replace(WhitespacePattern, " ")
        .trim()

private fun String.decodeBasicHtmlEntities(): String {
    val namedDecoded = NamedEntities.entries.fold(this) { value, (entity, replacement) ->
        value.replace(entity, replacement)
    }
    return NumericEntityPattern.replace(namedDecoded) { match ->
        val rawCodePoint = match.groupValues[1]
        val radix = if (rawCodePoint.startsWith("x", ignoreCase = true)) 16 else 10
        val digits = rawCodePoint.removePrefix("x").removePrefix("X")
        digits.toIntOrNull(radix)
            ?.takeIf { Character.isValidCodePoint(it) }
            ?.let { String(Character.toChars(it)) }
            ?: match.value
    }
}
