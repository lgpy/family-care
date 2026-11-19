package io.github.lgpy.familycare.di

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every view model a screen asks Koin for must be declared in a Koin module.
 *
 * 0.3.0 and 0.4.0 shipped a Today card (`NextSevenDaysDashboardCard`) whose
 * `koinViewModel<NextSevenDaysViewModel>()` had no `viewModel { … }` definition:
 * the view model was unit-tested on its own, the build was green, and Today
 * crashed on open. Building the real graph needs Android, PowerSync and Supabase,
 * so this guard reads the sources instead: it collects the types requested with
 * `koinViewModel` across every module's `src/main`, the types constructed inside
 * `viewModel { … }` / named in `viewModelOf(::…)`, and fails on any request
 * without a definition.
 */
class KoinViewModelRegistrationTest {

    @Test
    fun `every requested view model has a Koin definition`() {
        val sources = mainSources()
        assertTrue("found no Kotlin sources under ${androidRoot()}", sources.isNotEmpty())

        val requested = sources.flatMap { requestedViewModels(it.readText()) }.toSortedSet()
        val defined = sources.flatMap { definedViewModels(it.readText()) }.toSet()

        assertTrue("found no koinViewModel requests; the scan is broken", requested.isNotEmpty())
        val missing = requested - defined
        assertTrue(
            "koinViewModel requests without a viewModel { } definition: $missing",
            missing.isEmpty(),
        )
    }

    private fun androidRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "android/ root (settings.gradle.kts) not found" }
    }

    private fun mainSources(): List<File> =
        androidRoot().walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()

    private companion object {
        val TYPED_REQUEST = Regex("""koinViewModel<\s*(\w+)\s*>""")
        val DECLARED_REQUEST = Regex(""":\s*(\w+)\s*=\s*koinViewModel\s*\(""")
        val DEFINITION = Regex("""viewModel\s*\{[^{}]*?(?:->)?\s*(\w+ViewModel)\s*\(""")
        val DEFINITION_OF = Regex("""viewModelOf\(\s*::(\w+)""")

        fun requestedViewModels(text: String): List<String> =
            TYPED_REQUEST.findAll(text).map { it.groupValues[1] }.toList() +
                DECLARED_REQUEST.findAll(text).map { it.groupValues[1] }.toList()

        fun definedViewModels(text: String): List<String> =
            DEFINITION.findAll(text).map { it.groupValues[1] }.toList() +
                DEFINITION_OF.findAll(text).map { it.groupValues[1] }.toList()
    }
}
