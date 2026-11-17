package io.github.lgpy.familycare.core.domain

/**
 * Identity of a care circle.
 *
 * Modules reference patients and circles by id only (D5), so this type is the
 * smallest shared contract the whole app agrees on. It is pure Kotlin: no
 * Android, no database, no serialization framework.
 */
@JvmInline
value class CareCircleId(val value: String) {
    init {
        require(value.isNotBlank()) { "A care circle id must not be blank" }
    }
}
