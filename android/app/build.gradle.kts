import java.util.Properties

plugins {
    id("familycare.android.application")
    id("familycare.android.compose")
}

/*
 * Environment values (build-plan M1: "Sign in with Google → Supabase session").
 *
 * Public identifiers only: android/environments/<name>.properties is committed
 * and its values end up inside the APK. The Supabase publishable key is meant
 * to be public; the service-role/admin key must never be put there
 * (security-and-privacy §3, §4).
 *
 * Only `dev` exists today. A prod file with the same keys can be dropped next
 * to it and selected with `-Pfamilycare.env=prod`; no other change is needed.
 */
val environmentName: String = providers.gradleProperty("familycare.env").getOrElse("dev")
val environmentFile = rootProject.layout.projectDirectory.file("environments/$environmentName.properties")
val environmentValues: Map<String, String> = Properties()
    .apply {
        val text = providers.fileContents(environmentFile).asText.orNull
            ?: throw GradleException(
                "Missing environment file ${environmentFile.asFile} " +
                    "(familycare.env=$environmentName). See android/environments/dev.properties.",
            )
        text.reader().use { load(it) }
    }
    .entries
    .associate { it.key.toString() to it.value.toString() }

fun environmentValue(key: String): String = environmentValues[key]
    ?: throw GradleException("Missing '$key' in ${environmentFile.asFile} (familycare.env=$environmentName)")

/** A Java/Kotlin string literal safe to embed in generated BuildConfig code. */
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.lgpy.familycare"

    defaultConfig {
        // D21: permanent application ID.
        applicationId = "io.github.lgpy.familycare"

        buildConfigField("String", "SUPABASE_URL", buildConfigString(environmentValue("supabase.url")))
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            buildConfigString(environmentValue("supabase.publishableKey")),
        )
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            buildConfigString(environmentValue("google.webClientId")),
        )
        // Not used yet: PowerSync is the next M1 step and reads this value then.
        buildConfigField("String", "POWERSYNC_URL", buildConfigString(environmentValue("powersync.url")))
    }

    buildFeatures {
        // AGP 9 does not generate BuildConfig unless asked.
        buildConfig = true
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
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Sign in with Google (D12): Credential Manager needs an Activity, so the
    // trigger is an app-module concern; the Supabase client stays in core/data.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
