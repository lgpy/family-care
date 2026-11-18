package io.github.lgpy.familycare.core.domain.circle

/**
 * Identity of a care circle.
 *
 * Modules reference patients and circles by id only (D5), so this type is the
 * smallest shared contract the whole app agrees on. It is pure Kotlin: no
 * Android, no database, no serialization framework.
 *
 * The database column is `core.care_circles.id` (uuid); the app treats ids as
 * opaque strings, because PowerSync and PostgreSQL exchange them as text.
 */
@JvmInline
value class CareCircleId(val value: String) {
    init {
        require(value.isNotBlank()) { "A care circle id must not be blank" }
    }
}
