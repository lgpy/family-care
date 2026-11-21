package io.github.lgpy.familycare.feature.medications.ui

import java.util.Locale

/*
 * "Take fasting" is not a column: it lives in the medication's free-text
 * instructions (`meds.medications.instructions`), the same way the appointment
 * form's "Fasting" chip writes into `appointments.preparation`. The form's
 * chip only adds or removes the phrase, so the stored value stays ordinary text
 * every screen (and the export) already shows, and a note someone typed by hand
 * ("Em jejum, com água") lights the chip up too.
 *
 * Matching covers both languages, because the same medicine can be opened on a
 * phone in the other one. Lowercased with [Locale.ROOT] so a Turkish phone's
 * dotted/dotless i cannot change the match.
 */

/** Whether [instructions] say the medicine is taken fasting, in either language. */
fun instructionsMentionFasting(instructions: String?): Boolean {
    val normalised = instructions?.lowercase(Locale.ROOT) ?: return false
    return FastingKeywords.any { it in normalised }
}

/**
 * The instructions with the fasting note added or removed.
 *
 * Turning it on puts [phrase] (the phone's wording) in front of whatever was
 * already written, separated by a comma; turning it on when the note already
 * mentions fasting changes nothing. Turning it off removes every fasting phrase
 * in either language and the separator it leaves behind, keeping the rest of
 * the note as written.
 */
fun toggleFastingInstructions(instructions: String, fasting: Boolean, phrase: String): String {
    if (fasting) {
        if (instructionsMentionFasting(instructions)) return instructions
        val rest = instructions.trim()
        return if (rest.isEmpty()) phrase else "$phrase$SEPARATOR$rest"
    }

    var remainder = instructions
    // Longest first, so "Em jejum" goes as a whole rather than leaving "Em".
    FastingPhrases.sortedByDescending { it.length }.forEach { candidate ->
        remainder = remainder.replace(candidate, " ", ignoreCase = true)
    }
    return remainder
        .split(',', ';')
        .map { it.trim { c -> c.isWhitespace() || c in EdgeSeparators } }
        .filter { it.isNotEmpty() }
        .joinToString(SEPARATOR)
}

private const val SEPARATOR = ", "

private val EdgeSeparators = charArrayOf('·', '-', '.')

/** Every phrase removed when fasting is turned off. */
private val FastingPhrases = listOf(
    "on an empty stomach",
    "empty stomach",
    "fasting",
    "em jejum",
    "jejum",
)

/** Words that mark a note as fasting. */
private val FastingKeywords = listOf("fasting", "empty stomach", "jejum")
