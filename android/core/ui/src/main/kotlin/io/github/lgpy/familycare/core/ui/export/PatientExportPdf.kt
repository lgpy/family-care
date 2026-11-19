package io.github.lgpy.familycare.core.ui.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Renders the readable PDF with Android's [PdfDocument] (D30).
 *
 * The layout (wrapping and page breaks) is [PdfLayout]'s, which is pure and
 * tested on the JVM; this class only measures with a [Paint] and draws.
 */
object PatientExportPdf {

    /** Writes [sections] to [output] as an A4 PDF. */
    fun write(sections: List<ExportSection>, output: OutputStream) {
        val paints = PdfTextStyle.entries.associateWith { style ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = style.sizePt
                typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                color = android.graphics.Color.BLACK
            }
        }
        val pages = PdfLayout.flow(
            sections = sections,
            measure = { text, style -> paints.getValue(style).measureText(text) },
        )

        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, lines ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(
                        PdfLayout.PAGE_WIDTH,
                        PdfLayout.PAGE_HEIGHT,
                        index + 1,
                    ).create(),
                )
                var y = PdfLayout.MARGIN
                lines.forEach { line ->
                    val paint = paints.getValue(line.style)
                    val height = PdfLayout.lineHeight(line.style)
                    // Baseline sits at the bottom of the line box.
                    val baseline = y + height - paint.descent()
                    if (line.text.isNotEmpty()) {
                        page.canvas.drawText(
                            line.text,
                            PdfLayout.MARGIN,
                            baseline,
                            paint,
                        )
                    }
                    y += height
                }
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
