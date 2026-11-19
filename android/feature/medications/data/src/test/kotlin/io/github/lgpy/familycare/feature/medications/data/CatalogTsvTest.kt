package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.feature.medications.domain.MedicationForm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged catalogue loader (D31), on a **small fixture** — never the real
 * asset, so the test stays fast and does not care when the catalogue is rebuilt.
 */
class CatalogTsvTest {

    private fun fixtureText(): String =
        checkNotNull(javaClass.getResourceAsStream("/catalog-fixture.tsv")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return bytes.toByteArray()
    }

    @Test
    fun `reads the gzipped fixture and searches it`() {
        val catalog = CatalogTsv.read(ByteArrayInputStream(gzip(fixtureText())))

        assertEquals("every registration is an entry until a search collapses packs", 5, catalog.size)

        val apixabano = catalog.search("apixabano")
        assertEquals("two packs collapse into one suggestion", 1, apixabano.size)
        assertEquals(listOf(20, 60), apixabano.single().packOptions)
        assertEquals(MedicationForm.TABLET, apixabano.single().form)
        assertFalse(apixabano.single().otc)

        val aspirin = catalog.search("acido").single()
        assertEquals("AAS", aspirin.name)
        assertEquals(MedicationForm.TABLET, aspirin.form)
        assertTrue(aspirin.otc)
    }

    @Test
    fun `a pack that is a bottle has no countable units`() {
        val catalog = CatalogTsv.read(ByteArrayInputStream(gzip(fixtureText())))
        val topical = catalog.search("acarilbial").single()
        assertTrue(topical.packOptions.isEmpty())
        assertEquals(MedicationForm.CREAM, topical.form)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a different header fails loudly`() {
        CatalogTsv.parse("registo\tname\n1\tSomething\n")
    }

    @Test
    fun `the asset is parsed once`() = runBlocking {
        var opens = 0
        val repository = AssetMedicationCatalogRepository(
            openAsset = {
                opens++
                ByteArrayInputStream(gzip(fixtureText()))
            },
            io = Dispatchers.Unconfined,
        )

        assertEquals("Brilique", repository.search("bri").single().name)
        assertEquals("AAS", repository.search("aas").single().name)
        assertEquals(1, opens)
    }

    @Test
    fun `a short query never opens the asset`() = runBlocking {
        var opens = 0
        val repository = AssetMedicationCatalogRepository(
            openAsset = {
                opens++
                ByteArrayInputStream(gzip(fixtureText()))
            },
            io = Dispatchers.Unconfined,
        )

        assertTrue(repository.search("a").isEmpty())
        assertEquals("one character is not a search", 0, opens)
    }
}
