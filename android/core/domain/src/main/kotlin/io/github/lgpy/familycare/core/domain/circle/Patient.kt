package io.github.lgpy.familycare.core.domain.circle

import kotlin.time.Instant

/** Identity of one `core.patients` row. Generated on the phone, so it works offline. */
@JvmInline
value class PatientId(val value: String) {
    init {
        require(value.isNotBlank()) { "A patient id must not be blank" }
    }
}

/**
 * How reminders are routed for a patient (docs/domain/care-circle.md).
 *
 * [ROTA] is modelled because the database enum has it, but it is not built and
 * the app does not offer it: [isOffered] is false and [PatientRules.validate]
 * rejects saving a patient with it.
 */
enum class ReminderPolicy(val serverValue: String) {
    /** Primary caregiver is reminded; the server escalates to the others. */
    PRIMARY_WITH_ESCALATION("primary_with_escalation"),

    /** Every caregiver is reminded; the first confirmation wins. */
    ALL_CAREGIVERS("all_caregivers"),

    /** Who is on duty depends on day/time. Modelled, not built. */
    ROTA("rota"),

    ;

    /** Whether the app may offer this policy today. */
    val isOffered: Boolean get() = this != ROTA

    companion object {
        /** The policy with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): ReminderPolicy? =
            entries.firstOrNull { it.serverValue == value }

        /** The policies a screen should show in a picker. */
        val offered: List<ReminderPolicy> = entries.filter { it.isOffered }
    }
}

/**
 * A person whose health is managed. **Not** an account (docs/domain/care-circle.md).
 *
 * A patient belongs to exactly one circle; [linkedUserId] optionally ties it to
 * the account of a user who is also a patient (self-managed care). Instances
 * come from the synced local database, so they are only as fresh as the last
 * download. Build a new one with [PatientRules.newPatient] so the defaults from
 * the domain document (self-managed → primary = linked user) are applied, and
 * persist it with [PatientRepository.save].
 *
 * @param linkedUserId `auth.users.id` when the patient also uses the app, else null.
 * @param primaryCaregiverId Who is reminded first for
 *   [ReminderPolicy.PRIMARY_WITH_ESCALATION]. Must be an owner or caregiver of
 *   the circle (the server enforces it; [PatientRules.validate] checks it too
 *   when it has the member list).
 * @param escalationDelayMinutes Minutes after the reminder before the server
 *   alerts the other caregivers. 0..1440, default 30.
 * @param missedWindowMinutes After this many minutes an unconfirmed dose shows as
 *   missed. 1..1440, default 120.
 * @param agreementNote Optional free-text note about the patient's agreement
 *   (security-and-privacy §7). At most 2000 characters, never blocks anything.
 * @param archivedAt Set when the patient is archived (read-only, hidden from
 *   Today, no reminders). Null while active.
 */
data class Patient(
    val id: PatientId,
    val circleId: CareCircleId,
    val displayName: String,
    val linkedUserId: String? = null,
    val reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
    val primaryCaregiverId: String? = null,
    val escalationDelayMinutes: Int = PatientRules.DEFAULT_ESCALATION_DELAY_MINUTES,
    val missedWindowMinutes: Int = PatientRules.DEFAULT_MISSED_WINDOW_MINUTES,
    val agreementNote: String? = null,
    val archivedAt: Instant? = null,
) {

    /** Whether this patient is archived (read-only for the app). */
    val isArchived: Boolean get() = archivedAt != null

    /**
     * The values as they should be stored: the name trimmed, a blank agreement
     * note turned into null. Everything else is passed through.
     */
    fun normalized(): Patient = copy(
        displayName = PatientRules.normalizeName(displayName),
        agreementNote = PatientRules.normalizeNote(agreementNote),
    )

    /** Intrinsic validity (lengths and ranges), mirroring the database checks. */
    fun validate(): PatientValidation = PatientRules.validate(this)

    /**
     * Validity including the circle's members: the linked user must belong to
     * the circle, and the primary caregiver must be an owner or caregiver in it.
     */
    fun validateAgainst(members: List<CircleMember>): PatientValidation =
        PatientRules.validate(this, members)
}

/** The fields of a patient that a rule rejected. */
data class PatientValidation(val errors: Set<CoreField> = emptySet()) {
    /** Whether the patient can be stored as it is. */
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * The patient rules from docs/domain/care-circle.md, in one place.
 *
 * The numbers mirror the M2 migration's check constraints
 * (`patients_display_name_length`, `patients_escalation_delay_range`,
 * `patients_missed_window_range`, `patients_agreement_note_length`) so a local
 * write is rejected before it reaches the upload queue, where a constraint
 * failure could only be reported as a permanent error.
 */
object PatientRules {

    /** Mirrors `patients_display_name_length`. */
    const val MAX_NAME_LENGTH: Int = 100

    /** Mirrors `patients_agreement_note_length`. */
    const val MAX_AGREEMENT_NOTE_LENGTH: Int = 2000

    /** Mirrors `patients_escalation_delay_range`. */
    const val MIN_ESCALATION_DELAY_MINUTES: Int = 0
    const val MAX_ESCALATION_DELAY_MINUTES: Int = 1440

    /** Mirrors `patients_missed_window_range`. */
    const val MIN_MISSED_WINDOW_MINUTES: Int = 1
    const val MAX_MISSED_WINDOW_MINUTES: Int = 1440

    const val DEFAULT_ESCALATION_DELAY_MINUTES: Int = 30
    const val DEFAULT_MISSED_WINDOW_MINUTES: Int = 120

    /**
     * The primary caregiver a new patient gets: the linked user when the patient
     * is also an app user (self-managed, D11), otherwise the caregiver chosen in
     * the form.
     */
    fun defaultPrimaryCaregiver(
        linkedUserId: String?,
        chosenPrimaryCaregiverId: String?,
    ): String? = linkedUserId ?: chosenPrimaryCaregiverId

    /**
     * A patient that has never been stored, with the documented defaults.
     *
     * @param id Generated on the phone ([PatientRepository.newPatient] does it),
     *   so the row exists offline.
     * @param chosenPrimaryCaregiverId Ignored when [linkedUserId] is set: a
     *   self-managed patient is always their own primary caregiver.
     */
    fun newPatient(
        id: PatientId,
        circleId: CareCircleId,
        displayName: String,
        linkedUserId: String? = null,
        chosenPrimaryCaregiverId: String? = null,
        reminderPolicy: ReminderPolicy = ReminderPolicy.PRIMARY_WITH_ESCALATION,
        escalationDelayMinutes: Int = DEFAULT_ESCALATION_DELAY_MINUTES,
        missedWindowMinutes: Int = DEFAULT_MISSED_WINDOW_MINUTES,
        agreementNote: String? = null,
    ): Patient = Patient(
        id = id,
        circleId = circleId,
        displayName = normalizeName(displayName),
        linkedUserId = linkedUserId,
        reminderPolicy = reminderPolicy,
        primaryCaregiverId = defaultPrimaryCaregiver(linkedUserId, chosenPrimaryCaregiverId),
        escalationDelayMinutes = escalationDelayMinutes,
        missedWindowMinutes = missedWindowMinutes,
        agreementNote = normalizeNote(agreementNote),
    )

    fun normalizeName(name: String): String = name.trim()

    /** A blank note is stored as null; nothing else is changed. */
    fun normalizeNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether [name] is acceptable (1..100 characters after trimming). */
    fun isValidName(name: String): Boolean =
        normalizeName(name).length in 1..MAX_NAME_LENGTH

    /** The intrinsic rules: lengths and ranges only. */
    fun validate(patient: Patient): PatientValidation {
        val errors = mutableSetOf<CoreField>()

        if (!isValidName(patient.displayName)) {
            errors += CoreField.PATIENT_DISPLAY_NAME
        }
        if (!patient.reminderPolicy.isOffered) {
            errors += CoreField.PATIENT_REMINDER_POLICY
        }
        if (patient.escalationDelayMinutes !in
            MIN_ESCALATION_DELAY_MINUTES..MAX_ESCALATION_DELAY_MINUTES
        ) {
            errors += CoreField.PATIENT_ESCALATION_DELAY
        }
        if (patient.missedWindowMinutes !in
            MIN_MISSED_WINDOW_MINUTES..MAX_MISSED_WINDOW_MINUTES
        ) {
            errors += CoreField.PATIENT_MISSED_WINDOW
        }
        val noteLength = normalizeNote(patient.agreementNote)?.length ?: 0
        if (noteLength > MAX_AGREEMENT_NOTE_LENGTH) {
            errors += CoreField.PATIENT_AGREEMENT_NOTE
        }

        return PatientValidation(errors)
    }

    /**
     * The intrinsic rules plus the circle relationships the server enforces in
     * `core.check_patient()`: the linked user must be a member of this circle,
     * and the primary caregiver must be an owner or caregiver of it.
     */
    fun validate(patient: Patient, members: List<CircleMember>): PatientValidation {
        val errors = validate(patient).errors.toMutableSet()

        patient.linkedUserId?.let { linkedUserId ->
            if (members.none { it.userId == linkedUserId }) {
                errors += CoreField.PATIENT_LINKED_USER
            }
        }
        patient.primaryCaregiverId?.let { primaryCaregiverId ->
            val member = members.firstOrNull { it.userId == primaryCaregiverId }
            if (member == null || !member.role.canWrite) {
                errors += CoreField.PATIENT_PRIMARY_CAREGIVER
            }
        }

        return PatientValidation(errors)
    }
}
