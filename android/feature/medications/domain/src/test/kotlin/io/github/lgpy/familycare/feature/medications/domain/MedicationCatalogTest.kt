package io.github.lgpy.familycare.feature.medications.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bundled-catalogue search (D31): accents, ranking, collapse, form mapping. */
class MedicationCatalogTest {

    private fun entry(
        name: String,
        dci: String = "",
        strength: String = "",
        form: String = "Comp. revest. p/ película",
        pack: String = "Blister - 20 unidade(s)",
        packUnits: Int? = 20,
        otc: Boolean = false,
        registo: String = name + strength + pack,
    ) = CatalogEntry(
        registo = registo,
        name = name,
        dci = dci,
        strength = strength,
        form = form,
        pack = pack,
        packUnits = packUnits,
        otc = otc,
    )

    private val catalogue = MedicationCatalog(
        listOf(
            entry("Ácido acetilsalicílico Generis", "Ácido acetilsalicílico", "100 mg", registo = "1"),
            entry("Ácido acetilsalicílico Tolife", "Ácido acetilsalicílico", "100 mg", registo = "2"),
            entry("Paracetamol Sandoz", "Paracetamol", "500 mg", registo = "3"),
            entry("Paracetamol Sandoz", "Paracetamol", "1000 mg", registo = "4"),
            entry("Metformina Alter", "Metformina", "500 mg", registo = "5"),
            entry("Ticagrelor Krka", "Ticagrelor", "60 mg", registo = "6"),
            entry("AAS", "Ácido acetilsalicílico", "500 mg", form = "Comprimido", registo = "7"),
            entry("Apixabano Alter", "", "2.5 mg", registo = "8"),
        ),
    )

    @Test
    fun `two letters are the minimum`() {
        assertTrue(catalogue.search("a").isEmpty())
        assertFalse(catalogue.search("aa").isEmpty())
    }

    @Test
    fun `search ignores accents and case`() {
        val found = catalogue.search("acido")
        assertTrue(found.any { it.name == "Ácido acetilsalicílico Generis" })
        assertTrue(found.any { it.name == "AAS" }, "the DCI matches too")
        assertEquals(found, catalogue.search("ÁCIDO"))
    }

    @Test
    fun `only word prefixes match, not the middle of a word`() {
        assertTrue(catalogue.search("salic").isEmpty())
        assertTrue(catalogue.search("parac").any { it.name == "Paracetamol Sandoz" })
    }

    @Test
    fun `a name that starts with the query ranks before a DCI match`() {
        val found = catalogue.search("met")
        assertEquals("Metformina Alter", found.first().name)

        // "aas" is a name and also a word in no DCI; a query matching a name
        // prefix must not be overtaken by a DCI-only match further down.
        val aas = catalogue.search("aas")
        assertEquals("AAS", aas.first().name)
    }

    @Test
    fun `ties sort by name and then by strength numerically`() {
        val strengths = catalogue.search("paracetamol").map { it.strength }
        assertEquals(listOf("500 mg", "1000 mg"), strengths)

        val names = catalogue.search("acido").map { it.name }
        assertEquals(
            listOf("Ácido acetilsalicílico Generis", "Ácido acetilsalicílico Tolife", "AAS"),
            names,
        )
    }

    @Test
    fun `packs of the same medicine collapse into one suggestion`() {
        val withPacks = MedicationCatalog(
            listOf(
                entry("Paracetamol Sandoz", "Paracetamol", "500 mg", pack = "Blister - 20 unidade(s)", packUnits = 20, registo = "a"),
                entry("Paracetamol Sandoz", "Paracetamol", "500 mg", pack = "Blister - 60 unidade(s)", packUnits = 60, registo = "b"),
                entry("Paracetamol Sandoz", "Paracetamol", "500 mg", pack = "Frasco - 1 unidade(s) - 200 ml", packUnits = null, registo = "c"),
            ),
        )
        val found = withPacks.search("paracetamol")
        assertEquals(1, found.size)
        assertEquals(listOf(20, 60), found.single().packOptions)
    }

    @Test
    fun `different strengths and forms stay separate`() {
        assertEquals(2, catalogue.search("paracetamol").size)
        val twoForms = MedicationCatalog(
            listOf(
                entry("Paracetamol Sandoz", "Paracetamol", "500 mg", form = "Comp.", registo = "a"),
                entry("Paracetamol Sandoz", "Paracetamol", "500 mg", form = "Xarope", registo = "b"),
            ),
        )
        val found = twoForms.search("paracetamol")
        assertEquals(setOf(MedicationForm.TABLET, MedicationForm.LIQUID), found.map { it.form }.toSet())
    }

    @Test
    fun `limit caps the suggestions`() {
        assertEquals(1, catalogue.search("paracetamol", limit = 1).size)
        assertTrue(catalogue.search("paracetamol", limit = 0).isEmpty())
    }

    @Test
    fun `a suggestion carries what the form fills in`() {
        val suggestion = catalogue.search("apixabano").single()
        assertEquals(MedicationForm.TABLET, suggestion.form)
        assertFalse(suggestion.otc)
        assertEquals("2.5 mg", suggestion.strength)
        assertNull(SearchText.strengthNumber(""))
    }

    @Test
    fun `otc suggestions are marked`() {
        val withOtc = MedicationCatalog(
            listOf(entry("AAS", "Ácido acetilsalicílico", "500 mg", form = "Comprimido", otc = true, registo = "otc")),
        )
        assertTrue(withOtc.search("aas").single().otc)
    }

    @Test
    fun `forms map from the Portuguese export text`() {
        assertEquals(MedicationForm.TABLET, MedicationFormText.toForm("Comp. revest. p/ película"))
        assertEquals(MedicationForm.TABLET, MedicationFormText.toForm("Comprimido"))
        assertEquals(MedicationForm.CAPSULE, MedicationFormText.toForm("Cáps."))
        assertEquals(MedicationForm.CAPSULE, MedicationFormText.toForm("Cápsula gelatinosa"))
        assertEquals(MedicationForm.DROPS, MedicationFormText.toForm("Gotas orais"))
        assertEquals(MedicationForm.INJECTION, MedicationFormText.toForm("Sol. injetável"))
        assertEquals(MedicationForm.INJECTION, MedicationFormText.toForm("Suspensão injetável"))
        assertEquals(MedicationForm.LIQUID, MedicationFormText.toForm("Xarope"))
        assertEquals(MedicationForm.LIQUID, MedicationFormText.toForm("Solução oral"))
        assertEquals(MedicationForm.CREAM, MedicationFormText.toForm("Creme"))
        assertEquals(MedicationForm.CREAM, MedicationFormText.toForm("Solução cutânea"))
        assertEquals(MedicationForm.INHALER, MedicationFormText.toForm("Suspensão para inalação"))
        assertEquals(MedicationForm.PATCH, MedicationFormText.toForm("Sistema transdérmico"))
        assertEquals(MedicationForm.OTHER, MedicationFormText.toForm("Supositório"))
    }

    @Test
    fun `circle medications rank first and are not repeated from the catalogue`() {
        val circle = listOf(
            Medication(
                id = MedicationId("med-1"),
                patientId = io.github.lgpy.familycare.core.domain.circle.PatientId("p"),
                name = "Paracetamol Ben-u-ron",
                activeIngredient = "Paracetamol",
                strength = "500 mg",
                form = MedicationForm.TABLET,
                stockUnit = "tablet",
            ),
        )
        val circleSuggestions = MedicationSuggestionRules.circleSuggestions("paracetamol", circle)
        assertEquals(1, circleSuggestions.size)
        assertTrue(circleSuggestions.single().fromCircle)

        val merged = MedicationSuggestionRules.merge(
            circle = circleSuggestions,
            catalogue = catalogue.search("paracetamol"),
            limit = 20,
        )
        assertEquals("Paracetamol Ben-u-ron", merged.first().name)
        assertEquals(3, merged.count { it.name.contains("Paracetamol", ignoreCase = true) })
    }

    @Test
    fun `a circle suggestion matches its active ingredient too`() {
        val circle = listOf(
            Medication(
                id = MedicationId("med-1"),
                patientId = io.github.lgpy.familycare.core.domain.circle.PatientId("p"),
                name = "Ben-u-ron",
                activeIngredient = "Paracetamol",
                strength = "500 mg",
                form = MedicationForm.TABLET,
                stockUnit = "tablet",
            ),
        )
        assertEquals(1, MedicationSuggestionRules.circleSuggestions("parac", circle).size)
    }

    @Test
    fun `ties by strength are numeric, not textual`() {
        assertTrue(SearchText.strengthNumber("500 mg")!! < SearchText.strengthNumber("1000 mg")!!)
        assertEquals(0, SearchText.strengthNumber("2,5 mg")!!.compareTo(SearchText.strengthNumber("2.5 mg")!!))
        assertNull(SearchText.strengthNumber("sem dosagem"))
    }
}
