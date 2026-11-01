package com.pocketbudget.app.data.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.pocketbudget.app.domain.model.OcrBox
import com.pocketbudget.app.domain.model.OcrLayout
import com.pocketbudget.app.domain.model.OcrLine
import com.pocketbudget.app.domain.model.OcrWord
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The PDF could not be turned into pages; [reason] says why, for the message shown. */
class PdfImportException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason { UNREADABLE, PASSWORD_PROTECTED, EMPTY }
}

/**
 * Page images from a PDF, and whether pages beyond the limit were left out. [text] holds,
 * per image, the lines printed in the PDF itself (positioned on that image when the PDF
 * provides positions), or null where the page has no embedded text (scans) or it cannot be read on this Android.
 */
class PdfPages(val images: List<ByteArray>, val truncated: Boolean, val text: List<List<OcrLine>?> = images.map { null })

/**
 * Turns PDF receipts (store e-receipts, saved or scanned documents) into page images
 * for the normal receipt pipeline, using the platform's offline PdfRenderer. Pages are
 * rendered at about 200 DPI, at a width OCR reads well. A tall page (a long till roll
 * saved as one page) is cut into several images, each at most [MAX_SLICE_HEIGHT] pixels
 * high, at blank gaps between printed lines so no line is split across two images.
 * Digital PDFs carry their text: on Android 15+ it is read exactly instead of by OCR.
 * Nothing is uploaded or kept unencrypted: the images then go through ReceiptImageStore.
 */
object PdfReceiptRenderer {

    const val MAX_IMAGES = 12
    const val MAX_SLICE_HEIGHT = 2800
    private const val DPI = 200f
    private const val MIN_WIDTH = 1100
    private const val MAX_WIDTH = 1700

    /** Reads the document straight from its provider; copies it privately only if the provider cannot seek. */
    fun render(context: Context, uri: Uri): PdfPages {
        val direct = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (direct != null) {
            val result = runCatching { direct.use(::render) }
            result.exceptionOrNull()?.let { if (it is PdfImportException && it.reason != PdfImportException.Reason.UNREADABLE) throw it }
            result.getOrNull()?.let { return it }
        }
        // Some providers hand out pipes; PdfRenderer needs a seekable file.
        val copy = File.createTempFile("receipt", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw PdfImportException(PdfImportException.Reason.UNREADABLE)
            input.use { stream ->
                copy.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > ReceiptImageProcessor.MAX_INPUT_BYTES) throw PdfImportException(PdfImportException.Reason.UNREADABLE)
                        out.write(buffer, 0, n)
                    }
                }
            }
            return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use(::render)
        } finally {
            copy.delete()
        }
    }

    fun render(descriptor: ParcelFileDescriptor): PdfPages {
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: SecurityException) {
            throw PdfImportException(PdfImportException.Reason.PASSWORD_PROTECTED, e)
        } catch (e: IOException) {
            throw PdfImportException(PdfImportException.Reason.UNREADABLE, e)
        }
        renderer.use {
            if (it.pageCount == 0) throw PdfImportException(PdfImportException.Reason.EMPTY)
            val images = mutableListOf<ByteArray>()
            val text = mutableListOf<List<OcrLine>?>()
            var truncated = false
            for (index in 0 until it.pageCount) {
                if (images.size >= MAX_IMAGES) {
                    truncated = true
                    break
                }
                it.openPage(index).use { page ->
                    for ((slice, words) in slices(page)) {
                        if (images.size >= MAX_IMAGES) {
                            truncated = true
                            break
                        }
                        images += slice
                        text += words
                    }
                }
            }
            return PdfPages(images, truncated, text)
        }
    }

    /**
     * Renders one page in horizontal slices, each ending on a blank row where possible,
     * with the page's embedded words (if any) that fall in each slice.
     */
    private fun slices(page: PdfRenderer.Page): List<Pair<ByteArray, List<OcrLine>?>> {
        val width = (page.width * DPI / 72f).roundToInt().coerceIn(MIN_WIDTH, MAX_WIDTH)
        val scale = width.toFloat() / page.width
        val fullHeight = ceil(page.height * scale).toInt().coerceAtLeast(1)
        val embedded = embeddedText(page, scale)
        val out = mutableListOf<Pair<ByteArray, List<OcrLine>?>>()
        var top = 0
        while (top < fullHeight) {
            val height = minOf(MAX_SLICE_HEIGHT, fullHeight - top)
            val bitmap = createBitmap(width, height)
            // PDFs are transparent where nothing is printed; receipts are read on white.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, Matrix().apply { setScale(scale, scale); postTranslate(0f, -top.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val last = top + height >= fullHeight
            val cut = if (last) height else blankRowNear(bitmap, height) ?: height
            val slice = if (cut == height) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, cut)
            // Skip slices with nothing printed (page margins, trailing white space).
            if (!isBlank(slice, 0, slice.height)) {
                val inSlice = when {
                    embedded == null -> null
                    embedded.words != null -> OcrLayout.rows(
                        0,
                        embedded.words.filter { (it.box.top + it.box.bottom) / 2 in top until top + cut }
                            .map { it.copy(box = OcrBox(it.box.left, it.box.top - top, it.box.right, it.box.bottom - top)) },
                    )
                    // Without positions every line goes with the page's first image, in order.
                    out.isEmpty() -> embedded.lines.mapIndexed { i, line -> OcrLine(0, i, line, 100) }
                    else -> emptyList()
                }
                out += jpeg(slice) to inSlice
            }
            top += cut
        }
        return out
    }

    /**
     * Text printed in the PDF, or null when there is none (a scanned page), it is unusable
     * (some writers store every glyph separately) or this Android cannot read it (before
     * 15). With positions, words are rebuilt into rows like OCR output; without them
     * (some Android versions return none), the PDF's own line order is used.
     */
    private fun embeddedText(page: PdfRenderer.Page, scale: Float): EmbeddedText? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        val contents = runCatching { page.textContents }.getOrNull() ?: return null
        val lines = contents.flatMap { content -> content.text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() } }
        if (lines.none { line -> line.any(Char::isLetterOrDigit) } || lines.sumOf { it.length } < lines.size * 3) return null
        val positioned = contents.all { it.bounds.isNotEmpty() }
        if (!positioned) return EmbeddedText(lines, null)
        val words = contents.flatMap { content ->
            val parts = content.text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
            val bounds = content.bounds
            val rects = if (parts.size == bounds.size) {
                bounds
            } else {
                // Spread the lines evenly over the object's overall area.
                val top = bounds.minOf { it.top }
                val step = (bounds.maxOf { it.bottom } - top) / parts.size
                parts.indices.map { i -> RectF(bounds.minOf { it.left }, top + i * step, bounds.maxOf { it.right }, top + (i + 1) * step) }
            }
            parts.zip(rects) { text, rect ->
                OcrWord(text, 100, OcrBox((rect.left * scale).roundToInt(), (rect.top * scale).roundToInt(), (rect.right * scale).roundToInt(), (rect.bottom * scale).roundToInt()))
            }
        }
        return EmbeddedText(lines, words)
    }

    /** A page's own text: its lines in order, and positioned words when available. */
    private class EmbeddedText(val lines: List<String>, val words: List<OcrWord>?)
    /** The lowest blank row in the bottom half of the slice, so the slice ends between lines. */
    private fun blankRowNear(bitmap: Bitmap, height: Int): Int? {
        for (y in height - 1 downTo height / 2) {
            if (isBlank(bitmap, y, y + 1)) return y + 1
        }
        return null
    }

    private fun isBlank(bitmap: Bitmap, fromRow: Int, toRow: Int): Boolean {
        val row = IntArray(bitmap.width)
        for (y in fromRow until toRow) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { Color.red(it) < 200 || Color.green(it) < 200 || Color.blue(it) < 200 }) return false
        }
        return true
    }

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
}
