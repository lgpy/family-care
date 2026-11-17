plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.core.notifications"
}

dependencies {
    implementation(project(":core:domain"))
}
