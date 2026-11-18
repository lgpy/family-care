plugins {
    id("familycare.android.library")
    id("familycare.android.compose")
}

android {
    namespace = "io.github.lgpy.familycare.core.ui"
}

dependencies {
    // The patient profile contracts name core types (PatientId), so the core
    // domain travels with them to the shell and the features (D29).
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.process)

    debugApi(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
}
