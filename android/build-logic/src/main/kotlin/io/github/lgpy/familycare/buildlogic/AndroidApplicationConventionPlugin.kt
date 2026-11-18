package io.github.lgpy.familycare.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Shared configuration for the Android application module.
 *
 * AGP 9 has built-in Kotlin support, so the Kotlin Android plugin is not
 * applied here; `android.compileOptions.targetCompatibility` also drives the
 * Kotlin jvmTarget.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                compileSdk = 36
                defaultConfig {
                    minSdk = 33
                    targetSdk = 36
                    versionCode = 3
                    versionName = "0.3.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }
}
