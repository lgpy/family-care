package io.github.lgpy.familycare.feature.appointments.form

import io.github.lgpy.familycare.core.domain.circle.MemberRole
import io.github.lgpy.familycare.feature.appointments.FakeAppointmentRepository
import io.github.lgpy.familycare.feature.appointments.FakeCareCircleRepository
import io.github.lgpy.familycare.feature.appointments.FakePatientRepository
import io.github.lgpy.familycare.feature.appointments.appointment
import io.github.lgpy.familycare.feature.appointments.entry
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentField
import io.github.lgpy.familycare.feature.appointments.domain.AppointmentKind
import io.github.lgpy.familycare.feature.appointments.grandmaId
import io.github.lgpy.familycare.feature.appointments.grandpaId
import io.github.lgpy.familycare.feature.appointments.testNow
import io.github.lgpy.familycare.feature.appointments.testToday
import io.github.lgpy.familycare.feature.appointments.ui.PreparationTag
import io.github.lgpy.familycare.feature.appointments.ui.parsePreparation
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The appointment form: validation, create, edit prefill, follow-up prefill. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentFormViewModelTest {

    private lateinit var appointments: FakeAppointmentRepository
    private lateinit var patients: FakePatientRepository
    private lateinit var circles: FakeCareCircleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        appointments = FakeAppointmentRepository()
        patients = FakePatientRepository()
        circles = FakeCareCircleRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        patientId: String? = null,
        appointmentId: String? = null,
        followUpOf: String? = null,
    ) = AppointmentFormViewModel(
        patientIdArg = patientId,
        appointmentId = appointmentId,
        followUpOfArg = followUpOf,
        appointmentRepository = appointments,
        patientRepository = patients,
        careCircleRepository = circles,
        zone = ZoneId.of("Europe/Lisbon"),
        now = { testNow },
    )

    @Test
    fun `saving without a patient marks the patient field`() = runTest {
        val viewModel = viewModel()
        viewModel.onTitle("Cardiologia")

        viewModel.save()

        assertTrue(AppointmentField.PATIENT in viewModel.uiState.value.errors)
        assertTrue(appointments.created.isEmpty())
    }

    @Test
    fun `a duration that is not a number is refused before writing`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onDuration("half an hour")

        viewModel.save()

        assertEquals(setOf(AppointmentField.DURATION), viewModel.uiState.value.errors)
        assertTrue(appointments.created.isEmpty())
    }

    @Test
    fun `a duration outside the server's range is refused`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onDuration("0")

        viewModel.save()

        assertEquals(setOf(AppointmentField.DURATION), viewModel.uiState.value.errors)
        assertTrue(appointments.created.isEmpty())
    }

    @Test
    fun `a valid form creates the appointment with the entered fields`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onKind(AppointmentKind.BLOOD_TEST)
        viewModel.onTitle("  Análises  ")
        viewModel.onDate(testToday.plusDays(2))
        viewModel.onTime(LocalTime.of(8, 30))
        viewModel.onDuration("15")
        viewModel.onPlace("Laboratório")
        viewModel.onDoctor("Dra. Silva")
        viewModel.onPreparationTagToggled(PreparationTag.FASTING, "Jejum")
        viewModel.onPreparationText("desde as 22:00")
        viewModel.onNotes("Levar resultados antigos")

        viewModel.save()

        val created = appointments.created.single()
        assertEquals(grandpaId, created.patientId)
        assertEquals(AppointmentKind.BLOOD_TEST, created.kind)
        assertEquals("Análises", created.title)
        assertEquals(LocalDateTime.of(testToday.plusDays(2), LocalTime.of(8, 30)), created.startsLocal)
        assertEquals("Europe/Lisbon", created.timeZone.id)
        assertEquals(15, created.durationMinutes)
        assertEquals("Laboratório", created.place)
        assertEquals("Dra. Silva", created.doctor)
        assertEquals("Jejum, desde as 22:00", created.preparation)
        assertEquals("Levar resultados antigos", created.notes)
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `the form defaults to the device zone and today`() = runTest {
        val state = viewModel().uiState.value

        assertEquals("Europe/Lisbon", state.timeZone)
        assertEquals(testToday, state.date)
    }

    @Test
    fun `editing prefills from the stored appointment and keeps its zone`() = runTest {
        appointments.setAppointment(
            appointment(
                id = "appointment-1",
                startsLocal = LocalDateTime.of(2026, 12, 1, 11, 45),
                timeZone = ZoneId.of("Europe/London"),
            ),
        )
        val viewModel = viewModel(appointmentId = "appointment-1")
        val prefilled = viewModel.uiState.first { it.prefilled }

        assertEquals(grandpaId.value, prefilled.patientId)
        assertEquals(LocalDateTime.of(2026, 12, 1, 11, 45), LocalDateTime.of(prefilled.date, prefilled.time))
        assertEquals("Europe/London", prefilled.timeZone)
        assertEquals("Cardiologia", prefilled.title)

        viewModel.onTitle("Cardiologia (nova data)")
        viewModel.save()

        val updated = appointments.updated.single()
        assertEquals("appointment-1", updated.id.value)
        assertEquals("Europe/London", updated.timeZone.id)
        assertEquals("Cardiologia (nova data)", updated.title)
        assertTrue(appointments.created.isEmpty())
    }

    @Test
    fun `a follow-up starts from the earlier appointment and links to it`() = runTest {
        appointments.setAppointment(
            appointment(id = "earlier", kind = AppointmentKind.EXAM, title = "TAC"),
        )
        val viewModel = viewModel(followUpOf = "earlier")
        val prefilled = viewModel.uiState.first { it.prefilled }

        assertEquals(grandpaId.value, prefilled.patientId)
        assertEquals(AppointmentKind.EXAM, prefilled.kind)
        assertEquals("TAC", prefilled.title)
        assertEquals("Hospital", prefilled.place)
        assertEquals("Dra. Silva", prefilled.doctor)
        assertEquals("earlier", prefilled.followUpOf)
        assertEquals(testToday, prefilled.date)

        viewModel.save()

        val created = appointments.created.single()
        assertEquals("earlier", created.followUpOf?.value)
        assertEquals(grandpaId, created.patientId)
    }

    @Test
    fun `a viewer cannot save`() = runTest {
        circles.roleFlow.value = MemberRole.VIEWER
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onTitle("Cardiologia")

        viewModel.save()

        assertFalse(viewModel.uiState.value.canWrite)
        assertTrue(appointments.created.isEmpty())
        assertNull(viewModel.uiState.value.saveError)
    }

    @Test
    fun `the date and time pickers are the stored local date-time`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)

        viewModel.onDate(LocalDate.of(2027, 1, 4))
        viewModel.onTime(LocalTime.of(7, 5))
        viewModel.save()

        assertEquals(
            LocalDateTime.of(2027, 1, 4, 7, 5),
            appointments.created.single().startsLocal,
        )
    }

    @Test
    fun `the patient passed by the patient tab is preselected and fixed`() = runTest {
        val state = viewModel(patientId = grandpaId.value).uiState.value

        assertEquals(grandpaId.value, state.patientId)
        assertTrue(state.patientFixed)
    }

    @Test
    fun `English preparation chips compose one string and read back`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onPreparationTagToggled(PreparationTag.FASTING, "Fasting")
        viewModel.onPreparationTagToggled(PreparationTag.RESULTS, "Bring results")

        viewModel.save()

        val stored = appointments.created.single().preparation
        assertEquals("Fasting, Bring results", stored)

        val parsed = parsePreparation(stored)
        assertEquals(
            mapOf(PreparationTag.FASTING to "Fasting", PreparationTag.RESULTS to "Bring results"),
            parsed.tags,
        )
        assertEquals("", parsed.freeText)
    }

    @Test
    fun `Portuguese preparation chips compose one string and read back`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onPreparationTagToggled(PreparationTag.FASTING, "Jejum")
        viewModel.onPreparationTagToggled(PreparationTag.SNS_CARD, "Cartão SNS")
        viewModel.onPreparationText("trazer a receita")

        viewModel.save()

        val stored = appointments.created.single().preparation
        assertEquals("Jejum, Cartão SNS, trazer a receita", stored)

        val parsed = parsePreparation(stored)
        assertEquals(
            mapOf(PreparationTag.FASTING to "Jejum", PreparationTag.SNS_CARD to "Cartão SNS"),
            parsed.tags,
        )
        assertEquals("trazer a receita", parsed.freeText)
    }

    @Test
    fun `a stored preparation is split into its chips and the rest on edit`() = runTest {
        appointments.setAppointment(
            appointment(id = "with-preparation", preparation = "Jejum desde as 22:00"),
        )

        val prefilled = viewModel(appointmentId = "with-preparation").uiState.first { it.prefilled }

        assertEquals(mapOf(PreparationTag.FASTING to "Jejum"), prefilled.preparationTags)
        assertEquals("desde as 22:00", prefilled.preparationFreeText)
    }

    @Test
    fun `toggling a selected chip off removes its phrase`() = runTest {
        val viewModel = viewModel(patientId = grandpaId.value)
        viewModel.onPreparationTagToggled(PreparationTag.FASTING, "Jejum")
        viewModel.onPreparationTagToggled(PreparationTag.FASTING, "Jejum")

        viewModel.save()

        assertNull(appointments.created.single().preparation)
    }

    @Test
    fun `recent places are the patient's latest past places, capped at three`() = runTest {
        appointments.agendaFlow.value = listOf(
            entry(
                appointment(
                    id = "p1",
                    startsLocal = LocalDateTime.of(2026, 10, 1, 9, 0),
                    place = "USF Lumiar",
                ),
            ),
            entry(
                appointment(
                    id = "p2",
                    startsLocal = LocalDateTime.of(2026, 10, 15, 9, 0),
                    place = "Hospital Santa Maria",
                ),
            ),
            entry(
                appointment(
                    id = "p3",
                    startsLocal = LocalDateTime.of(2026, 11, 1, 9, 0),
                    place = "Clínica Lumiar",
                ),
            ),
            entry(
                appointment(
                    id = "p4",
                    startsLocal = LocalDateTime.of(2026, 11, 10, 9, 0),
                    place = "USF Lumiar",
                ),
            ),
            entry(
                appointment(
                    id = "other-patient",
                    patientId = grandmaId,
                    startsLocal = LocalDateTime.of(2026, 11, 15, 9, 0),
                    place = "Outra clínica",
                ),
                patientName = "Grandma",
            ),
            entry(
                appointment(
                    id = "future",
                    startsLocal = LocalDateTime.of(2026, 12, 1, 9, 0),
                    place = "Futuro",
                ),
            ),
        )

        val state = viewModel(patientId = grandpaId.value)
            .uiState
            .first { it.recentPlaces.isNotEmpty() }

        assertEquals(
            listOf("USF Lumiar", "Clínica Lumiar", "Hospital Santa Maria"),
            state.recentPlaces,
        )
    }
}
