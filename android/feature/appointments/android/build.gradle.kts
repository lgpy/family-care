plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.feature.appointments"
}

// A feature module may depend on :core:* and on its own domain module, never on
// another feature (D5; enforced by :checkModuleBoundaries).
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:notifications"))
    implementation(project(":core:ui"))

    implementation(project(":feature:appointments:domain"))
}
