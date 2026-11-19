package io.github.lgpy.familycare.core.ui.export

/**
 * The styles of the PDF export. Sizes are in the PDF's points (1/72 inch), so
 * the layout is identical on every phone.
 */
enum class PdfTextStyle(val sizePt: Float, val bold: Boolean) {
    TITLE(18f, true),
    HEADING(13f, true),
    BODY(10f, false),
    EMPHASIS(10f, true),
    SMALL(9f, false),
}

/** One laid-out line of text, before it is drawn. */
data class PdfLine(val text: String, val style: PdfTextStyle)

/**
 * The pure layout of the PDF (D30): line wrapping and pagination, with the
 * measuring function passed in so a JVM test can drive it without Android.
 *
 * [PatientExportPdf] supplies `Paint.measureText` in production; the tests
 * supply a character-count measure.
 */
object PdfLayout {

    /** A4 at 72 dpi, the size `PdfDocument` takes. */
    const val PAGE_WIDTH: Int = 595
    const val PAGE_HEIGHT: Int = 842
    const val MARGIN: Float = 40f

    /** The width available for text on a page. */
    const val TEXT_WIDTH: Float = PAGE_WIDTH - 2 * MARGIN

    /** The height available for text on a page. */
    const val TEXT_HEIGHT: Float = PAGE_HEIGHT - 2 * MARGIN

    /** The line height of [style], as a multiple of its point size. */
    fun lineHeight(style: PdfTextStyle): Float = style.sizePt * 1.35f

    /**
     * Greedy word wrap of [text] to [maxWidth]. A word longer than the line is
     * split by character, so no text can run off the page.
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        measure: (String, PdfTextStyle) -> Float,
        style: PdfTextStyle = PdfTextStyle.BODY,
    ): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        text.split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate, style) <= maxWidth) {
                current.clear().append(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.clear()
                }
                if (measure(word, style) <= maxWidth) {
                    current.append(word)
                } else {
                    // One very long word: break it by character.
                    var chunk = StringBuilder()
                    word.forEach { character ->
                        val next = chunk.toString() + character
                        if (measure(next, style) <= maxWidth) {
                            chunk.append(character)
                        } else {
                            if (chunk.isNotEmpty()) lines += chunk.toString()
                            chunk = StringBuilder(character.toString())
                        }
                    }
                    current.append(chunk)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.ifEmpty { listOf("") }
    }

    /**
     * Every page's lines, for [sections] in the order given.
     *
     * A heading never ends a page alone: it moves to the next page together
     * with at least one line under it when the current page can only fit the
     * heading. Empty lines at the top of a page are dropped.
     */
    fun flow(
        sections: List<ExportSection>,
        maxWidth: Float = TEXT_WIDTH,
        maxHeight: Float = TEXT_HEIGHT,
        measure: (String, PdfTextStyle) -> Float,
        lineHeight: (PdfTextStyle) -> Float = { style -> PdfLayout.lineHeight(style) },
    ): List<List<PdfLine>> {
        val pages = mutableListOf<MutableList<PdfLine>>()
        var page = mutableListOf<PdfLine>()
        var used = 0f
        fun newPage() {
            pages += page
            page = mutableListOf()
            used = 0f
        }
        fun add(line: PdfLine) {
            val height = lineHeight(line.style)
            if (used + height > maxHeight && page.isNotEmpty()) newPage()
            page += line
            used += height
        }
        fun addLines(lines: List<PdfLine>) {
            lines.forEach(::add)
        }

        sections.forEachIndexed { index, section ->
            val block = mutableListOf<PdfLine>()
            if (index > 0) block += PdfLine("", PdfTextStyle.SMALL)
            block += PdfLine(section.title, PdfTextStyle.HEADING)
            section.blocks.forEach { content ->
                when (content) {
                    is ExportBlock.Paragraph -> wrap(content.text, maxWidth, measure, PdfTextStyle.EMPHASIS)
                        .forEach { line ->
                            block += PdfLine(
                                line,
                                if (content.emphasis) PdfTextStyle.EMPHASIS else PdfTextStyle.BODY,
                            )
                        }

                    is ExportBlock.Row ->
                        wrap("${content.label}: ${content.value}", maxWidth, measure, PdfTextStyle.BODY)
                            .forEach { line -> block += PdfLine(line, PdfTextStyle.BODY) }
                }
            }
            // Keep the heading with at least its first content line.
            val total = block.sumOf { lineHeight(it.style).toDouble() }.toFloat()
            if (page.isNotEmpty() && used + total > maxHeight && block.size > 2) {
                val first = block.first()
                if (used + lineHeight(first.style) + lineHeight(block[1].style) > maxHeight) newPage()
            }
            addLines(block)
        }
        pages += page
        return pages.map { lines ->
            lines.dropWhile { it.text.isEmpty() }.ifEmpty { emptyList() }
        }.filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) }
    }
}
