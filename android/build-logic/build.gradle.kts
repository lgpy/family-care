plugins {
    `kotlin-dsl`
}

group = "io.github.lgpy.familycare.buildlogic"

dependencies {
    implementation(libs.agp)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "familycare.android.application"
            implementationClass = "io.github.lgpy.familycare.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "familycare.android.library"
            implementationClass = "io.github.lgpy.familycare.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("kotlinJvm") {
            id = "familycare.kotlin.jvm"
            implementationClass = "io.github.lgpy.familycare.buildlogic.KotlinJvmConventionPlugin"
        }
        register("androidCompose") {
            id = "familycare.android.compose"
            implementationClass = "io.github.lgpy.familycare.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
