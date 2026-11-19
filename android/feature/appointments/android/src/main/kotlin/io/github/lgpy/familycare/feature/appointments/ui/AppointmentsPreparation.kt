package io.github.lgpy.familycare.feature.appointments.ui

/*
 * The "Before going" chips and the free-text preparation field speak the same
 * stored value: one plain string (`appointments.preparation`), exactly as the
 * column has always held it (docs/domain/appointments.md).
 *
 * The chips are only a convenient way to write the phrases the app itself
 * offers, so the stored value stays ordinary text the server and the other
 * screens read. Parsing matches either language, because the same appointment
 * can be opened on a phone in the other one, and leaves anything the chips do
 * not know as free text.
 */

/** The things the "Before going" section offers as one tap. */
enum class PreparationTag {
    FASTING,
    RESULTS,
    SNS_CARD,
    MEDICINES,
    ;

    /**
     * Every phrase the app has offered for this tag, in both languages.
     *
     * Matching any of them on edit keeps a preparation written in the other
     * language visible as a selected chip instead of turning it into free text.
     */
    val phrases: List<String>
        get() = when (this) {
            FASTING -> listOf("Fasting", "Jejum")
            RESULTS -> listOf("Bring results", "Trazer resultados")
            SNS_CARD -> listOf("SNS card", "Cartão SNS")
            MEDICINES -> listOf("Bring medicines", "Trazer medicamentos")
        }
}

/** A stored preparation split into the chips it mentions and the rest. */
data class ParsedPreparation(
    /** The matched tags in [PreparationTag] order, each with the phrase as stored. */
    val tags: Map<PreparationTag, String>,
    /** Everything the chips do not cover, trimmed. */
    val freeText: String,
)

/**
 * Splits [preparation] into the chip phrases it contains and the remaining free
 * text.
 *
 * Each tag matches its first phrase in either language (case-insensitive) and
 * that phrase is removed from the remainder, so "Jejum desde as 22:00" becomes
 * the fasting chip plus "desde as 22:00". The matched phrase is kept as it was
 * written: editing an appointment never silently rewrites it into the phone's
 * language.
 */
fun parsePreparation(preparation: String?): ParsedPreparation {
    val note = preparation?.takeIf { it.isNotBlank() } ?: return ParsedPreparation(emptyMap(), "")
    var remainder = note
    val tags = LinkedHashMap<PreparationTag, String>()

    PreparationTag.entries.forEach { tag ->
        val match = tag.phrases
            .mapNotNull { phrase ->
                val index = remainder.indexOf(phrase, ignoreCase = true)
                if (index < 0) {
                    null
                } else {
                    Triple(index, phrase, remainder.substring(index, index + phrase.length))
                }
            }
            .minByOrNull { it.first }

        if (match != null) {
            tags[tag] = match.third
            remainder = remainder.removeRange(match.first, match.first + match.second.length)
        }
    }

    return ParsedPreparation(tags = tags, freeText = remainder.trimSeparators())
}

/**
 * Builds the stored string from the selected chips and the free text: the chip
 * phrases as they were chosen, then the free text, separated by a comma.
 *
 * Returns null when nothing is left, so an empty section stores no value at all
 * (the column is optional).
 */
fun composePreparation(tags: Map<PreparationTag, String>, freeText: String): String? {
    val parts = mutableListOf<String>()
    tags.values.forEach { phrase ->
        phrase.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    }
    freeText.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    return parts.joinToString(SEPARATOR).takeIf { it.isNotEmpty() }
}

private const val SEPARATOR = ", "

/** Trims whitespace and the separators a removed chip can leave behind. */
private fun String.trimSeparators(): String =
    trim { it.isWhitespace() || it in Separators }

private val Separators = charArrayOf(',', ';', '·', '-', '.')
