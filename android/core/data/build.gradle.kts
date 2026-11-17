plugins {
    id("familycare.android.library")
}

android {
    namespace = "io.github.lgpy.familycare.core.data"
}

dependencies {
    implementation(project(":core:domain"))
}
