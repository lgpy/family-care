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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // NotificationCompat / NotificationManagerCompat for the dose reminders.
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // M4 push (D4/D27): the feature's own message types and notifications, plus
    // the short job that applies a `clear` while the app is in the background.
    // The FCM service and token registration live in :core:notifications (D29).
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
