package io.github.lgpy.familycare.feature.medications.domain

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * Medication-name search over the catalogue bundled in the app (D31).
 *
 * The catalogue is INFARMED's public lists, joined by registration number and
 * shipped as an asset (`meds_catalog.tsv.gzip`); this class is the pure part: it
 * holds [CatalogEntry] rows and answers [search]. Loading the asset, and uniting
 * the result with the circle's own medications, live in the data layer and the
 * form's view model.
 *
 * Search is deliberately small and local: matching is accent- and
 * case-insensitive, needs two characters, and only matches **word prefixes** —
 * "acido" finds "Ácido acetilsalicílico", "salic" does not. Ranking puts a name
 * that starts with the query first, then a DCI that does, then any word prefix
 * in the name (all of these beat a DCI-only match). Ties sort by name, then by
 * strength numerically, so "500 mg" comes before "1000 mg" rather than after
 * it as text.
 *
 * Rows that differ only in pack ("Blister - 20" vs "Blister - 60") collapse into
 * one suggestion per (name, strength, form); the packs stay available as
 * [MedicationSuggestion.packOptions] so the form can offer "1 pack = 60".
 */
class MedicationCatalog(entries: List<CatalogEntry>) {

    private val entries: List<CatalogEntry> = entries.filter { it.name.isNotBlank() }

    /** Every entry, for tests and diagnostics. */
    val size: Int get() = entries.size

    /**
     * Up to [limit] suggestions for [query], best first and without pack
     * duplicates. Blank or one-character queries return nothing: a dropdown
     * after one letter is noise, and the caller keeps free text anyway.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<MedicationSuggestion> {
        if (limit <= 0) return emptyList()
        val normalised = SearchText.normalize(query)
        if (normalised.length < MIN_QUERY_LENGTH) return emptyList()

        val matched = entries.mapNotNull { entry ->
            val tier = SearchText.tier(
                query = normalised,
                name = SearchText.normalize(entry.name),
                dci = SearchText.normalize(entry.dci),
            ) ?: return@mapNotNull null
            entry to tier
        }
        return collapse(matched, limit)
    }

    /**
     * Groups the ranked matches by (name, strength, form), keeping the best one
     * and collecting every pack size seen for the group.
     */
    private fun collapse(matched: List<Pair<CatalogEntry, Int>>, limit: Int): List<MedicationSuggestion> {
        val ranked = matched.sortedWith(
            compareBy<Pair<CatalogEntry, Int>> { it.second }
                .thenBy { SearchText.normalize(it.first.name) }
                .thenBy(nullsLast(naturalOrder())) { SearchText.strengthNumber(it.first.strength) }
                .thenBy { it.first.strength },
        )

        val packs = LinkedHashMap<String, MutableList<Int>>()
        val representatives = LinkedHashMap<String, CatalogEntry>()
        for ((entry, _) in ranked) {
            val key = SearchText.collapseKey(entry.name, entry.strength, entry.form)
            representatives.getOrPut(key) { entry }
            if (entry.packUnits != null && entry.packUnits > 0) {
                packs.getOrPut(key) { mutableListOf() }.let { if (entry.packUnits !in it) it += entry.packUnits }
            }
        }

        return representatives.entries.take(limit).map { (key, entry) ->
            MedicationSuggestion(
                name = entry.name,
                dci = entry.dci,
                strength = entry.strength,
                form = MedicationFormText.toForm(entry.form),
                formText = entry.form,
                packOptions = packs[key].orEmpty().sorted(),
                otc = entry.otc,
                holder = entry.holder,
                registo = entry.registo,
            )
        }
    }

    companion object {
        const val MIN_QUERY_LENGTH: Int = 2
        const val DEFAULT_LIMIT: Int = 20
    }
}

/** One row of the bundled catalogue: a registration number and its medicine. */
data class CatalogEntry(
    val registo: String,
    val name: String,
    val dci: String = "",
    val strength: String = "",
    /** INFARMED's own form text (`Comp. revest. p/ película`). */
    val form: String = "",
    val pack: String = "",
    val packUnits: Int? = null,
    /** True when the registration is on the MNSRM (no prescription) list. */
    val otc: Boolean = false,
    val holder: String = "",
)

/**
 * What the form shows under a name field, and what picking it fills in.
 *
 * [form] is the app's enum ([MedicationFormText] maps INFARMED's text to it);
 * [formText] keeps the raw text for display, because "Comp. revest. p/
 * película" says more than "Tablet" and the user can still edit everything.
 */
data class MedicationSuggestion(
    val name: String,
    val dci: String = "",
    val strength: String = "",
    val form: MedicationForm = MedicationForm.OTHER,
    val formText: String = "",
    val packOptions: List<Int> = emptyList(),
    val otc: Boolean = false,
    val holder: String = "",
    val registo: String = "",
    /** True when the suggestion came from a medication already in the circle. */
    val fromCircle: Boolean = false,
) {
    /**
     * True when the suggestion already makes a usable medication row: a name
     * with neither strength nor form is still worth offering, but the form
     * screen fills only what it has.
     */
    val hasDetails: Boolean get() = strength.isNotBlank() || formText.isNotBlank()
}

/**
 * Turns INFARMED's form text into the app's [MedicationForm].
 *
 * The exports write forms in Portuguese ("Comp. revest. p/ película", "Cáps.",
 * "Sol. injetável", "Solução cutânea"). Matching is accent-free and ordered:
 * capsules before the "gel" in "cápsulas gelatinosas", injections before the
 * "solução" that would otherwise read as a liquid, topical "cutânea" before
 * liquids. Anything unrecognised is [MedicationForm.OTHER] — the screen lets the
 * user change it.
 */
object MedicationFormText {

    fun toForm(text: String): MedicationForm {
        val value = SearchText.normalize(text)
        if (value.isEmpty()) return MedicationForm.OTHER
        return when {
            value.contains("caps") -> MedicationForm.CAPSULE
            value.contains("comp") -> MedicationForm.TABLET
            value.contains("gotas") -> MedicationForm.DROPS
            value.contains("inal") -> MedicationForm.INHALER
            value.contains("adesivo") || value.contains("transderm") -> MedicationForm.PATCH
            value.contains("creme") || value.contains("pomada") || value.contains("unguento") ||
                value.contains("cutan") || value.contains("gel") -> MedicationForm.CREAM
            value.contains("injec") || value.contains("injet") -> MedicationForm.INJECTION
            value.contains("xarope") || value.contains("elixir") || value.contains("oral") ->
                MedicationForm.LIQUID
            else -> MedicationForm.OTHER
        }
    }
}

/**
 * The circle-first merge the form applies on top of the catalogue.
 *
 * A family tends to re-add the medicines it already has, so a medication
 * already in the circle that matches the query is ranked before the catalogue:
 * it carries the name the household actually uses, and picking it changes
 * nothing. The catalogue entry with the same name/strength/form is dropped
 * instead of being shown twice.
 */
object MedicationSuggestionRules {

    /** The circle's medications that match [query], best first, as suggestions. */
    fun circleSuggestions(
        query: String,
        medications: List<Medication>,
        limit: Int = MedicationCatalog.DEFAULT_LIMIT,
    ): List<MedicationSuggestion> {
        if (limit <= 0) return emptyList()
        val normalised = SearchText.normalize(query)
        if (normalised.length < MedicationCatalog.MIN_QUERY_LENGTH) return emptyList()

        return medications
            .mapNotNull { medication ->
                val tier = SearchText.tier(
                    query = normalised,
                    name = SearchText.normalize(medication.name),
                    dci = SearchText.normalize(medication.activeIngredient.orEmpty()),
                ) ?: return@mapNotNull null
                tier to medication
            }
            .sortedWith(
                compareBy<Pair<Int, Medication>> { it.first }
                    .thenBy { SearchText.normalize(it.second.name) }
                    .thenBy(nullsLast(naturalOrder())) { SearchText.strengthNumber(it.second.strength.orEmpty()) },
            )
            .map { (_, medication) ->
                MedicationSuggestion(
                    name = medication.name,
                    dci = medication.activeIngredient.orEmpty(),
                    strength = medication.strength.orEmpty(),
                    form = medication.form,
                    formText = "",
                    otc = !medication.requiresPrescription,
                    fromCircle = true,
                )
            }
            .distinctBy { SearchText.collapseKey(it.name, it.strength, it.formText) }
            .take(limit)
    }

    /** [circle] first, then [catalogue], without repeating a medicine. */
    fun merge(
        circle: List<MedicationSuggestion>,
        catalogue: List<MedicationSuggestion>,
        limit: Int = MedicationCatalog.DEFAULT_LIMIT,
    ): List<MedicationSuggestion> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        val merged = ArrayList<MedicationSuggestion>(minOf(limit, circle.size + catalogue.size))
        for (suggestion in circle + catalogue) {
            if (!seen.add(SearchText.collapseKey(suggestion.name, suggestion.strength, suggestion.formText))) continue
            merged += suggestion
            if (merged.size >= limit) break
        }
        return merged
    }
}

/**
 * Text handling shared by the catalogue and the circle merge.
 *
 * Folding to NFD and dropping the combining marks is what makes "acido" find
 * "Ácido"; [Locale.ROOT] keeps the lowercasing independent of the phone's
 * language (Turkish "I" would otherwise break it).
 */
object SearchText {

    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val FIRST_NUMBER = Regex("(\\d+(?:[.,]\\d+)?)")

    /** Lowercase, accent-free, single-spaced. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return MARKS.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(SPACES, " ")
            .trim()
    }

    private fun words(text: String): List<String> =
        text.split(NON_WORD).filter { it.isNotEmpty() }

    /**
     * The ranking tier of [name]/[dci] for an already-normalised [query], or
     * null when it does not match. 0: name starts with it; 1: DCI starts with
     * it; 2: every query word prefixes a word in the name; 3: same, in the DCI.
     */
    fun tier(query: String, name: String, dci: String): Int? {
        if (query.isEmpty()) return null
        if (name.startsWith(query)) return 0
        if (dci.isNotEmpty() && dci.startsWith(query)) return 1

        val queryWords = words(query)
        if (queryWords.isEmpty()) return null
        val nameWords = words(name)
        if (queryWords.all { token -> nameWords.any { it.startsWith(token) } }) return 2
        val dciWords = if (dci.isEmpty()) emptyList() else words(dci)
        if (dciWords.isNotEmpty() && queryWords.all { token -> dciWords.any { it.startsWith(token) } }) return 3
        return null
    }

    /**
     * The number at the start of a strength ("500 mg" → 500, "2.5 mg" → 2.5,
     * "10 mg/5 ml" → 10), or null when there is none. Text sort would put
     * "1000 mg" before "500 mg"; this keeps the doses in order.
     */
    fun strengthNumber(strength: String): BigDecimal? {
        val match = FIRST_NUMBER.find(normalize(strength)) ?: return null
        return runCatching { BigDecimal(match.groupValues[1].replace(',', '.')) }.getOrNull()
    }

    /** The identity of a suggestion: same name, strength and form, any pack. */
    fun collapseKey(name: String, strength: String, form: String): String =
        normalize(name) + "|" + normalize(strength) + "|" + normalize(form)
}
