plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.core.notifications"
}

// The single FCM service and token registration (M6; D29). Features contribute
// `PushHandler`s for their own message types; nothing here names a feature (D5).
dependencies {
    implementation(project(":core:domain"))

    implementation(libs.kotlinx.coroutines.core)

    // `firebase-messaging` only: no analytics or other Firebase product (D17).
    // The google-services plugin is applied by the app module, which owns the
    // generated resources.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // The registration job retries while offline.
    implementation(libs.androidx.work.runtime.ktx)
    // Workers reach the graph through `GlobalContext`, like the reminder receivers.
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
