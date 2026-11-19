package io.github.lgpy.familycare.di

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Contributions (dashboard cards, patient tabs and headers, timeline and export
 * sources, push handlers) are several definitions of ONE type gathered with
 * `getAll<T>()`. Koin keys a definition by type + qualifier, so two unqualified
 * `single<T>` silently override each other and `getAll` returns only the last:
 * 0.3.0–0.4.1 shipped with one patient tab, one Today card and one push handler
 * live. Every contribution must therefore carry its own `named(...)` qualifier.
 */
class KoinContributionTest {

    private interface Contribution
    private class A : Contribution
    private class B : Contribution

    @Test
    fun `unqualified duplicates override each other (why the rule exists)`() {
        val koin = koinApplication {
            modules(module {
                single<Contribution> { A() }
                single<Contribution> { B() }
            })
        }.koin
        assertEquals(1, koin.getAll<Contribution>().size)
    }

    @Test
    fun `named contributions are all returned by getAll`() {
        val koin = koinApplication {
            modules(module {
                single<Contribution>(named("a")) { A() }
                single<Contribution>(named("b")) { B() }
            })
        }.koin
        assertEquals(2, koin.getAll<Contribution>().size)
    }

    @Test
    fun `no type is declared twice without a qualifier in the app's sources`() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile
        requireNotNull(root)

        val unqualified = Regex("""\bsingle<\s*(\w+)\s*>\s*\{""")
        val duplicates = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .flatMap { file -> unqualified.findAll(file.readText()).map { it.groupValues[1] } }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }

        assertTrue(
            "types declared more than once with an unqualified single<T> { } " +
                "(only the last survives; add named(...)): $duplicates",
            duplicates.isEmpty(),
        )
    }
}
