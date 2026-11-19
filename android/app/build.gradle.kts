import java.util.Properties

plugins {
    id("familycare.android.application")
    id("familycare.android.compose")
    // M4 push: turns android/app/google-services.json into the Firebase
    // resources the FCM client reads. The file is committed because its values
    // are public Firebase identifiers (like android/environments): the API key
    // ships inside every APK anyway and grants only Firebase's own services.
    alias(libs.plugins.google.services)
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

/*
 * Release signing (build-plan §7, D18). The key and its passwords live in the
 * password manager and an offline copy, never in the repository: a local,
 * git-ignored android/keystore.properties points at the keystore file:
 *
 *   storeFile=C:/Users/<you>/family-care-release-key/family-care-release.jks
 *   storePassword=...
 *   keyAlias=family-care
 *   keyPassword=...
 *
 * Without that file (CI, other machines) release builds are simply unsigned.
 */
val releaseSigning: Properties? = rootProject.layout.projectDirectory.file("keystore.properties")
    .let { providers.fileContents(it).asText.orNull }
    ?.let { text -> Properties().apply { text.reader().use { load(it) } } }

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

    if (releaseSigning != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // A debug build is a separate app from the release build the family
            // uses: its own package, its own (dev) backend session and its own
            // data. Without the suffix both ids are io.github.lgpy.familycare, so
            // installing a debug APK would overwrite (or be blocked as a
            // signature conflict by) the real release-signed app on the phone.
            applicationIdSuffix = ".debug"
            // The debug app only talks to the dev backend (test data), and the
            // lock blocks adb-driven checks such as screenshots at 200% font.
            // Release, and a debug build pointed at any other environment, keep
            // the mandatory lock (D12).
            buildConfigField("boolean", "APP_LOCK_ENABLED", (environmentName != "dev").toString())
        }
        getByName("release") {
            buildConfigField("boolean", "APP_LOCK_ENABLED", "true")
        }
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

    // The medications module's data layer: its synced tables are composed into
    // the PowerSync schema, and its repositories are exposed to the screens (D5:
    // the app wires modules; the core never names one).
    implementation(project(":feature:medications:data"))
    // The module's domain types, used by the export source this app assembles
    // from the module's public data API (D30/D5).
    implementation(project(":feature:medications:domain"))

    // The medications module's screens, view models and dashboard card (M3). The
    // app wires the module; no feature depends on another (D5).
    implementation(project(":feature:medications:android"))

    // The patient-info module's data layer (its `info.patient_info` table joins
    // the synced schema and its repository is exposed to Koin) and its screens
    // (M6/D29: the profile's allergies banner, Info tab and edit form).
    implementation(project(":feature:patient-info:data"))
    implementation(project(":feature:patient-info:domain"))
    implementation(project(":feature:patient-info:android"))
    // M6 appointments (D29): its synced table joins the PowerSync schema and its
    // screens are wired into the shell's navigation.
    implementation(project(":feature:appointments:data"))
    implementation(project(":feature:appointments:domain"))
    implementation(project(":feature:appointments:android"))

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
    // The export's JSON document (D30) is built with kotlinx.serialization.
    implementation(libs.kotlinx.serialization.json)

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
