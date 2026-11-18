package io.github.lgpy.familycare.feature.patientinfo.domain

/**
 * Patients are identified by the core type
 * [io.github.lgpy.familycare.core.domain.circle.PatientId]: feature modules
 * reference patients by id only (D5), so the app has exactly one patient
 * identity. This alias keeps the feature's imports short without introducing a
 * second, incompatible type.
 */
typealias PatientId = io.github.lgpy.familycare.core.domain.circle.PatientId
