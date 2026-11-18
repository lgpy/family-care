plugins {
    id("familycare.android.library")
    id("familycare.android.compose")
}

android {
    namespace = "io.github.lgpy.familycare.feature.medications"
}

// A feature module may depend on :core:* and on its own domain and data
// modules, never on another feature (D5; enforced by :checkModuleBoundaries).
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:notifications"))
    implementation(project(":core:ui"))

    implementation(project(":feature:medications:domain"))
    implementation(project(":feature:medications:data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // NotificationCompat / NotificationManagerCompat for the dose reminders.
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // M4 push (D4/D27): escalation and clear messages, plus the short jobs that
    // run the clear while the app is in the background. `firebase-messaging`
    // only: no analytics or other Firebase product (D17). The google-services
    // plugin is applied by the app module, which owns the generated resources.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
