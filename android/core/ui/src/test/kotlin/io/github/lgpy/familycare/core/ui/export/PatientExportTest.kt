package io.github.lgpy.familycare.core.ui.export

import io.github.lgpy.familycare.core.domain.circle.CareCircleId
import io.github.lgpy.familycare.core.domain.circle.Patient
import io.github.lgpy.familycare.core.domain.circle.PatientId
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The export document's shape and the PDF layout's wrapping and pagination (D30). */
class PatientExportTest {

    private val patient = Patient(
        id = PatientId("patient-1"),
        circleId = CareCircleId("circle-1"),
        displayName = "Avó Maria",
        agreementNote = "nota",
    )

    private class FakeSource(
        override val key: String,
        private val payload: JsonElement,
    ) : PatientExportSource {

        override suspend fun json(patientId: PatientId): JsonElement = payload

        override suspend fun pdfSections(patient: Patient): List<ExportSection> = listOf(
            ExportSection(
                order = 10,
                title = key,
                blocks = listOf(ExportBlock.Paragraph("first line")),
            ),
        )
    }

    @Test
    fun documentHasTheFixedShapeAndEverySource() = kotlinx.coroutines.test.runTest {
        val sources = PatientExportSources(
            listOf(
                FakeSource("medications", buildJsonArray { add(JsonPrimitive("x")) }),
                FakeSource("appointments", JsonArray(emptyList())),
            ),
        )
        val document = sources.json(patient, Instant.parse("2026-11-19T10:00:00Z"))

        assertEquals("family-care-patient-export", document.getValue("format").jsonPrimitive.content)
        assertEquals(1, document.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("2026-11-19T10:00:00Z", document.getValue("exported_at").jsonPrimitive.content)
        val patientJson = document.getValue("patient") as JsonObject
        assertEquals("patient-1", patientJson.getValue("id").jsonPrimitive.content)
        assertEquals("nota", patientJson.getValue("agreement_note").jsonPrimitive.content)
        assertTrue(document.containsKey("medications"))
        assertTrue(document.containsKey("appointments"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun twoSourcesCannotClaimTheSameKey() {
        PatientExportSources(
            listOf(
                FakeSource("dose_log", JsonArray(emptyList())),
                FakeSource("dose_log", JsonArray(emptyList())),
            ),
        )
    }

    @Test
    fun wrappingRespectsTheWidthAndKeepsEveryWord() {
        val measure = { text: String, _: PdfTextStyle -> text.length * 6f }
        val lines = PdfLayout.wrap("one two three four five six", 60f, measure)

        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length * 6f <= 60f })
        assertEquals(
            "one two three four five six".split(' ').joinToString(" "),
            lines.joinToString(" "),
        )
    }

    @Test
    fun aLongWordIsSplitInsteadOfOverflowing() {
        val measure = { text: String, _: PdfTextStyle -> text.length * 6f }
        val lines = PdfLayout.wrap("abcdefghijkl", 30f, measure)

        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length * 6f <= 30f })
        assertEquals("abcdefghijkl", lines.joinToString(""))
    }

    @Test
    fun aLongDocumentBreaksIntoPages() {
        val sections = (1..20).map { index ->
            ExportSection(
                order = index,
                title = "Section $index",
                blocks = listOf(
                    ExportBlock.Paragraph("a short line"),
                    ExportBlock.Row("label", "value"),
                ),
            )
        }
        val pages = PdfLayout.flow(
            sections = sections,
            maxHeight = 120f,
            measure = { text, _ -> text.length * 6f },
        )

        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.isNotEmpty() })
        assertEquals(
            sections.size,
            pages.flatten().count { it.style == PdfTextStyle.HEADING },
        )
    }

    @Test
    fun fileNamesAreSanitized() {
        assertEquals(
            "family-care-Avó-Maria-20261119.json",
            PatientExportFiles.fileName("Avó / Maria", "20261119", "json"),
        )
        assertEquals("patient", PatientExportFiles.sanitize("///"))
        assertEquals("family-care-patient-20261119.pdf", PatientExportFiles.fileName("  ", "20261119", "pdf"))
    }
}
