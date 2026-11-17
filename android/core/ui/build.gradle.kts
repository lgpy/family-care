plugins {
    id("familycare.android.library")
    id("familycare.android.compose")
}

android {
    namespace = "io.github.lgpy.familycare.core.ui"
}

dependencies {
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
