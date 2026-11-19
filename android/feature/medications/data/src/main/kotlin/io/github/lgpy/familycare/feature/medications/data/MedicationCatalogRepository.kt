package io.github.lgpy.familycare.feature.medications.data

import io.github.lgpy.familycare.feature.medications.domain.CatalogEntry
import io.github.lgpy.familycare.feature.medications.domain.MedicationCatalog
import io.github.lgpy.familycare.feature.medications.domain.MedicationSuggestion
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Medication-name search over the catalogue bundled in the APK (D31).
 *
 * The catalogue is a build artifact (`ops/meds-catalog/build.py`), so a search
 * never leaves the phone: which medicines a family takes is health data (D4,
 * D10). The interface is deliberately small — the form asks for suggestions by
 * text — so a test, or a future online catalogue, can stand in for the asset.
 */
interface MedicationCatalogRepository {

    /** Up to [limit] suggestions for [query]; empty below two characters. */
    suspend fun search(
        query: String,
        limit: Int = MedicationCatalog.DEFAULT_LIMIT,
    ): List<MedicationSuggestion>
}

/**
 * [MedicationCatalogRepository] over the packaged asset.
 *
 * The gzipped TSV is read and parsed **once**, lazily, on [io]: the first
 * search pays for it and every later keystroke is a lookup in memory. The
 * mutex keeps two concurrent first searches from parsing it twice.
 *
 * [openAsset] returns the asset stream and is the only Android-shaped thing
 * here (the app supplies `context.assets.open(...)`), which keeps this file
 * compilable in the module's JVM test project.
 */
class AssetMedicationCatalogRepository(
    private val openAsset: () -> InputStream,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MedicationCatalogRepository {

    private val mutex = Mutex()

    @Volatile
    private var cached: MedicationCatalog? = null

    override suspend fun search(query: String, limit: Int): List<MedicationSuggestion> {
        // Cheap guard before the lazy load: one letter never needs the asset.
        if (limit <= 0 || query.trim().length < MedicationCatalog.MIN_QUERY_LENGTH) return emptyList()
        return catalog().search(query, limit)
    }

    private suspend fun catalog(): MedicationCatalog {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(io) {
                CatalogTsv.read(openAsset()).also { cached = it }
            }
        }
    }
}

/**
 * Reads `meds_catalog.tsv.gz`: gzipped UTF-8 with a header row.
 *
 * The columns are the builder's (`registo name dci strength form pack
 * pack_units otc holder`). Parsing is strict about the header — a mismatched
 * asset is a build mistake that should fail loudly — and forgiving about
 * missing trailing cells in a row.
 */
object CatalogTsv {

    const val ASSET_NAME: String = "meds_catalog.tsv.gz"

    val HEADER: List<String> =
        listOf("registo", "name", "dci", "strength", "form", "pack", "pack_units", "otc", "holder")

    /** Reads a gzipped catalogue, closing [input]. */
    fun read(input: InputStream): MedicationCatalog =
        GZIPInputStream(BufferedInputStream(input)).use { gzipped ->
            parse(gzipped.bufferedReader(Charsets.UTF_8).readText())
        }

    /** Parses the plain TSV text (the gzip layer's payload). */
    fun parse(text: String): MedicationCatalog {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "meds catalogue is empty" }

        val header = lines.first().trimEnd('\r').split('\t')
        require(header == HEADER) {
            "unexpected meds catalogue header: $header (expected $HEADER)"
        }

        val entries = ArrayList<CatalogEntry>(lines.size)
        for (line in lines.drop(1)) {
            val cells = line.trimEnd('\r').split('\t')
            if (cells.size < 2) continue
            entries += CatalogEntry(
                registo = cells.getOrElse(0) { "" },
                name = cells.getOrElse(1) { "" },
                dci = cells.getOrElse(2) { "" },
                strength = cells.getOrElse(3) { "" },
                form = cells.getOrElse(4) { "" },
                pack = cells.getOrElse(5) { "" },
                packUnits = cells.getOrElse(6) { "" }.toIntOrNull()?.takeIf { it > 0 },
                otc = cells.getOrElse(7) { "0" } == "1",
                holder = cells.getOrElse(8) { "" },
            )
        }
        return MedicationCatalog(entries)
    }
}
