package io.github.lgpy.familycare.ui.activity

import androidx.annotation.StringRes
import io.github.lgpy.familycare.core.data.activity.ActivityEvent
import io.github.lgpy.familycare.core.ui.R
import java.time.LocalDate
import java.time.ZoneId

/** One localized sentence: a string resource and the arguments it formats. */
data class ActivitySentence(
    @StringRes val template: Int,
    val args: List<String>,
)

/** One day of the feed, newest day first; [events] stay newest first. */
data class ActivityDay(
    val date: LocalDate,
    val events: List<ActivityEvent>,
)

/**
 * Turns one audit row into the sentence the feed shows (D30).
 *
 * The mapping is `(table_name, action)` to a template, exactly as
 * docs/domain/patient-lifecycle.md §Activity describes it: the server sends a
 * short label and the names, never the audit payloads, so nothing clinical can
 * leak into the sentence. [someone] is the already-localized fallback for a
 * name the row no longer resolves ("Someone"/"Alguém").
 *
 * This is a pure function so the mapping is testable without Compose.
 */
fun activitySentence(event: ActivityEvent, someone: String): ActivitySentence {
    val actor = event.actorName.orPlaceholder(someone)
    val patient = event.patientName.orPlaceholder(someone)
    val label = event.label.orPlaceholder(someone)
    val thing = event.label ?: event.patientName ?: event.tableName

    return when (event.action) {
        ACTION_SIGN_IN -> ActivitySentence(R.string.activity_signed_in, listOf(actor))
        ACTION_SESSION_ENDED -> ActivitySentence(R.string.activity_signed_out, listOf(actor))
        ACTION_PATIENT_ERASED -> ActivitySentence(R.string.activity_erased_patient, listOf(actor))
        ACTION_PATIENT_EXPORTED ->
            ActivitySentence(R.string.activity_exported_patient_data, listOf(actor, patient))

        ACTION_INSERT, ACTION_UPDATE, ACTION_DELETE -> when (event.table()) {
            TABLE_MEDICATIONS -> when (event.action) {
                ACTION_INSERT -> ActivitySentence(
                    R.string.activity_added_medication,
                    listOf(actor, label, patient),
                )

                ACTION_UPDATE -> ActivitySentence(
                    R.string.activity_updated_medication,
                    listOf(actor, label, patient),
                )

                else -> ActivitySentence(
                    R.string.activity_removed_medication,
                    listOf(actor, label, patient),
                )
            }

            TABLE_DOSE_LOG -> ActivitySentence(
                R.string.activity_recorded_dose,
                listOf(actor, label, patient),
            )

            TABLE_APPOINTMENTS -> when (event.action) {
                ACTION_INSERT -> ActivitySentence(
                    R.string.activity_added_appointment,
                    listOf(actor, label, patient),
                )

                ACTION_UPDATE -> ActivitySentence(
                    R.string.activity_updated_appointment,
                    listOf(actor, label, patient),
                )

                else -> ActivitySentence(
                    R.string.activity_removed_appointment,
                    listOf(actor, label, patient),
                )
            }

            TABLE_PATIENT_INFO -> ActivitySentence(
                R.string.activity_updated_patient_info,
                listOf(actor, patient),
            )

            TABLE_PATIENTS -> {
                val patientThing = event.patientName ?: event.label ?: event.tableName
                when (event.action) {
                    ACTION_INSERT -> ActivitySentence(
                        R.string.activity_added_patient,
                        listOf(actor, patientThing),
                    )

                    ACTION_UPDATE -> ActivitySentence(
                        R.string.activity_updated_patient,
                        listOf(actor, patientThing),
                    )

                    else -> ActivitySentence(
                        R.string.activity_removed_patient,
                        listOf(actor, patientThing),
                    )
                }
            }

            TABLE_MEMBERSHIPS -> ActivitySentence(R.string.activity_changed_circle, listOf(actor))

            TABLE_INVITATIONS ->
                ActivitySentence(R.string.activity_changed_invitations, listOf(actor))

            else -> ActivitySentence(R.string.activity_changed, listOf(actor, thing))
        }

        else -> ActivitySentence(R.string.activity_changed, listOf(actor, thing))
    }
}

/** Groups [events] (newest first) into the days of [zone], newest day first. */
fun activityDays(events: List<ActivityEvent>, zone: ZoneId): List<ActivityDay> = events
    .groupBy { event ->
        java.time.Instant.ofEpochMilli(event.occurredAt.toEpochMilliseconds())
            .atZone(zone)
            .toLocalDate()
    }
    .map { (date, dayEvents) -> ActivityDay(date, dayEvents.sortedByDescending { it.occurredAt }) }
    .sortedByDescending { it.date }

private fun String?.orPlaceholder(fallback: String): String =
    this?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

/** The table's own name, without a schema prefix (`meds.medications` → `medications`). */
private fun ActivityEvent.table(): String =
    tableName.substringAfterLast('.').lowercase()

private const val ACTION_INSERT = "insert"
private const val ACTION_UPDATE = "update"
private const val ACTION_DELETE = "delete"
private const val ACTION_SIGN_IN = "auth.sign_in"
private const val ACTION_SESSION_ENDED = "auth.session_ended"
private const val ACTION_PATIENT_ERASED = "patient.erased"
private const val ACTION_PATIENT_EXPORTED = "patient.exported"

private const val TABLE_MEDICATIONS = "medications"
private const val TABLE_DOSE_LOG = "dose_log"
private const val TABLE_APPOINTMENTS = "appointments"
private const val TABLE_PATIENT_INFO = "patient_info"
private const val TABLE_PATIENTS = "patients"
private const val TABLE_MEMBERSHIPS = "memberships"
private const val TABLE_INVITATIONS = "invitations"
