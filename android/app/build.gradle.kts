plugins {
    id("familycare.android.application")
    id("familycare.android.compose")
}

android {
    namespace = "io.github.lgpy.familycare"

    defaultConfig {
        // D21: permanent application ID.
        applicationId = "io.github.lgpy.familycare"
    }

    // Generates the locale config declared by the merged manifest, so the
    // languages the app ships in (en, pt-PT) also appear under Android's
    // per-app language settings. Languages are declared in the resources of
    // the modules below; res/resources.properties names the unqualified one.
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:notifications"))
    implementation(project(":core:ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
}
