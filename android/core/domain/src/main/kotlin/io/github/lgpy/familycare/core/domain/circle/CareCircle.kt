package io.github.lgpy.familycare.core.domain.circle

/**
 * The unit of sharing and isolation ("our family").
 *
 * Joining a circle gives access to its patients; circles never see each other's
 * data (docs/domain/care-circle.md). The MVP gives a user at most one circle, but
 * [CareCircleRepository] still defines which one wins when the server holds more
 * than one (the oldest membership).
 *
 * Instances come from the synced local database, so they are only as fresh as
 * the last download. Creation goes through [CareCircleRepository.createCircle].
 */
data class CareCircle(
    val id: CareCircleId,
    val name: String,
) {
    companion object {
        /** Mirrors `care_circles_name_length` in the M2 migration. */
        const val MAX_NAME_LENGTH: Int = 100

        /** The value [CareCircleRepository.createCircle] sends: the server trims too. */
        fun normalizeName(name: String): String = name.trim()

        /**
         * Whether [name] is acceptable, using the same rule as the
         * `care_circles_name_length` check (1..100 characters after trimming).
         */
        fun isValidName(name: String): Boolean =
            normalizeName(name).length in 1..MAX_NAME_LENGTH
    }
}
