plugins {
    id("familycare.kotlin.jvm")
}

// Pure Kotlin rules for the patient-info feature. It depends on the core domain
// so a patient is identified by the single core PatientId (D5); apart from that
// it uses nothing but the Kotlin standard library (and tests).
dependencies {
    api(project(":core:domain"))
}
