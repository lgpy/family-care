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
    // The small built-in icon set (Warning, Info, Check, ShoppingCart). Only
    // `material-icons-core` is used; `icons-extended` is not a dependency.
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.kotlinx.serialization.json)

    debugApi(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
