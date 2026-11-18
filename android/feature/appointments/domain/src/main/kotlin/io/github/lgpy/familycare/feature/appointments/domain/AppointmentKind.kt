package io.github.lgpy.familycare.feature.appointments.domain

/**
 * What an appointment is (`appointments.appointment_kind` on the server).
 *
 * The server value is the enum label; the app shows a localized label instead
 * (`appointments_kind_*` strings). [OTHER] pairs with the free-text title, but
 * every kind may carry one.
 */
enum class AppointmentKind(val serverValue: String) {
    CONSULTATION("consultation"),
    BLOOD_TEST("blood_test"),
    EXAM("exam"),
    CHECKUP("checkup"),
    OTHER("other"),
    ;

    companion object {

        /** The kind with this database value, or null when it is not one we know. */
        fun fromServer(value: String?): AppointmentKind? =
            entries.firstOrNull { it.serverValue == value }
    }
}
