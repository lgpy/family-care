package io.github.lgpy.familycare.feature.medications.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the packaged catalogue's file name. The Android build decompresses
 * any `.gz` asset and drops the extension, so the app's `assets.open` would
 * miss it on a phone while the parser tests above still pass.
 */
class CatalogAssetTest {

    @Test
    fun `the asset the app opens exists and is not named gz`() {
        assertFalse(CatalogTsv.ASSET_NAME.endsWith(".gz"))
        val asset = File("src/main/assets", CatalogTsv.ASSET_NAME)
        assertTrue("missing ${asset.absolutePath}", asset.isFile)
    }

    @Test
    fun `the packaged asset parses`() {
        val catalog = CatalogTsv.read(File("src/main/assets", CatalogTsv.ASSET_NAME).inputStream())
        assertTrue(catalog.size > 1000)
    }
}
