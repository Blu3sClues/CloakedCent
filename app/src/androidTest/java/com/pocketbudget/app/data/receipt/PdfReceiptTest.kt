package com.pocketbudget.app.data.receipt

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.core.money.Money
import com.pocketbudget.app.data.repository.LedgerTestDatabase
import com.pocketbudget.app.data.repository.PDF_TEXT_ENGINE
import com.pocketbudget.app.data.repository.RoomReceiptRepository
import com.pocketbudget.app.domain.model.ImportOutcome
import com.pocketbudget.app.domain.model.MeasureUnit
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.model.ReceiptMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import kotlin.time.Duration.Companion.minutes

/** PDF receipts: rendered offline into page images, then read by the normal OCR pipeline. */
@RunWith(AndroidJUnit4::class)
class PdfReceiptTest {

    @get:Rule
    val t = LedgerTestDatabase()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "pdf-test").apply { deleteRecursively(); mkdirs() }
    private val store = File(context.noBackupFilesDir, "pdf-test-images")
    private lateinit var receipts: RoomReceiptRepository

    @Before
    fun setUp() {
        store.deleteRecursively()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        receipts = RoomReceiptRepository({ t.db }, t.ledger, t.payees, ReceiptImageStore(store, { key }), TesseractOcrEngine(context))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        store.deleteRecursively()
    }

    /** Writes a PDF like a store e-receipt: one 80 mm wide page per list of lines, text in points. */
    private fun pdf(name: String, vararg pages: List<String>, pageHeight: Int? = null): File {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            color = Color.BLACK
        }
        pages.forEachIndexed { index, lines ->
            val height = pageHeight ?: (lines.size * 12 + 40)
            val page = document.startPage(PdfDocument.PageInfo.Builder(227, height, index + 1).create())
            lines.forEachIndexed { i, line -> page.canvas.drawText(line, 12f, 24f + i * 12, paint) }
            document.finishPage(page)
        }
        return File(dir, name).also { file -> file.outputStream().use(document::writeTo); document.close() }
    }

    /**
     * A digital e-receipt PDF as point-of-sale systems and browsers write it: real text
     * objects in the standard Courier font, one per printed line (80 mm wide page).
     */
    private fun textPdf(name: String, lines: List<String>): File {
        val height = lines.size * 12 + 40
        val content = buildString {
            lines.forEachIndexed { i, line ->
                val escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                append("BT /F1 9 Tf 12 ${height - 24 - i * 12} Td ($escaped) Tj ET\n")
            }
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 227 $height] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n${content}endstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body ->
            val offset = out.length
            out.append("${i + 1} 0 obj\n$body\nendobj\n")
            offset
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File(dir, name).also { it.writeBytes(out.toString().toByteArray(Charsets.ISO_8859_1)) }
    }

    private fun render(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(PdfReceiptRenderer::render)

    private fun cents(c: Long) = "%d.%02d".format(c / 100, c % 100)

    @Test
    fun eachPdfPageBecomesAReadablePageImage() {
        val pages = render(pdf("two-pages.pdf", ReceiptPhotos.GROCERY, listOf("PAGE TWO", "RETURN POLICY 30 DAYS")))
        assertEquals(2, pages.images.size)
        assertTrue("not truncated", !pages.truncated)
        pages.images.forEach { jpeg ->
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            // An 80 mm receipt is enlarged so its print is tall enough for OCR.
            assertTrue("width ${bitmap.width}", bitmap.width in 1100..1700)
            assertTrue("height ${bitmap.height}", bitmap.height <= PdfReceiptRenderer.MAX_SLICE_HEIGHT)
        }
        // The PDF's own text is read exactly (Android 15+), line by line.
        assertEquals(
            ReceiptPhotos.GROCERY.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() },
            pages.text[0]!!.map { it.text },
        )
        assertEquals(listOf("PAGE TWO", "RETURN POLICY 30 DAYS"), pages.text[1]!!.map { it.text })
        // Rendering is deterministic, so the same PDF is recognised as a duplicate later.
        assertTrue("deterministic", render(pdf("again.pdf", ReceiptPhotos.GROCERY)).images.first().contentEquals(pages.images.first()))
    }

    @Test
    fun longTillRollsAreSlicedBetweenLinesNeverThroughThem() {
        val lines = (1..90).map { "LINE %02d ITEM TEXT".format(it) }
        val pages = render(pdf("long.pdf", lines))
        assertTrue("sliced: ${pages.images.size}", pages.images.size >= 2)
        pages.images.dropLast(1).forEach { jpeg ->
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertTrue(bitmap.height <= PdfReceiptRenderer.MAX_SLICE_HEIGHT)
            // The cut falls on blank paper: the bottom row has no print.
            val row = IntArray(bitmap.width).also { bitmap.getPixels(it, 0, bitmap.width, 0, bitmap.height - 1, bitmap.width, 1) }
            assertTrue(row.all { Color.red(it) > 180 })
        }
    }

    @Test
    fun unreadableAndEmptyFilesAreRefusedClearly() {
        val garbage = File(dir, "not.pdf").apply { writeText("this is not a pdf") }
        try {
            render(garbage)
            fail("not a PDF")
        } catch (e: PdfImportException) {
            assertEquals(PdfImportException.Reason.UNREADABLE, e.reason)
        }
        // Blank pages produce no images rather than empty receipts.
        assertTrue(render(pdf("blank.pdf", emptyList(), pageHeight = 300)).images.isEmpty())
        // The Uri path used by the app works on the same files.
        assertEquals(2, PdfReceiptRenderer.render(context, Uri.fromFile(pdf("uri.pdf", listOf("A"), listOf("B")))).images.size)
    }

    @Test
    fun pdfReceiptsAreReadIntoItemsAndDuplicatesAreCaught() = runTest(timeout = 5.minutes) {
        val file = pdf("grocery.pdf", ReceiptPhotos.GROCERY)
        val created = receipts.import(render(file).images.map(::PageInput), "pdf-1") as ImportOutcome.Created
        receipts.runOcr(created.receiptId, dayFirst = false)
        val r = receipts.observeDetail(created.receiptId).first()!!.review
        assertEquals(listOf(349L, 279L, 218L, 387L, 500L, 429L), r.items.map { it.lineTotal.cents })
        assertEquals("BANANAS", r.items[2].description)
        assertEquals(1_250L to MeasureUnit.KG, r.items[2].quantityMilli to r.items[2].unit)
        assertEquals(Money(2_148), r.total)
        assertTrue(ReceiptMath.reconcile(r).isEmpty())
        assertEquals(0L, t.count("SELECT COUNT(*) FROM transactions"))
        // Importing the same PDF again is recognised as the same receipt.
        assertEquals(ImportOutcome.Duplicate(created.receiptId), receipts.import(render(file).images.map(::PageInput), "pdf-2"))
    }

    @Test
    fun longPdfReceiptKeepsEveryItemExactlyOnceAcrossSlices() = runTest(timeout = 8.minutes) {
        val prices = (1..60).map { 100L + it * 7 }
        val names = listOf("APPLES", "BREAD", "CHEESE", "DONUTS", "EGGS", "FLOUR", "GRAPES", "HONEY", "JAM", "KALE")
        val lines = listOf("MEGA MART", "2026-11-14 10:15", "") +
            prices.mapIndexed { i, p -> "%-14s %s".format("${names[i % 10]} ${'A' + i / 10}", cents(p).padStart(7)) } +
            listOf("", "SUBTOTAL ${cents(prices.sum()).padStart(13)}", "TOTAL    ${cents(prices.sum()).padStart(13)}")
        val pages = render(textPdf("long-receipt.pdf", lines))
        assertTrue("several slices: ${pages.images.size}", pages.images.size >= 2)
        // A digital PDF: its own text is used, exactly, instead of OCR.
        assertTrue("embedded text found", pages.text.all { it != null })
        val inputs = pages.images.mapIndexed { i, image -> PageInput(image, embeddedText = pages.text[i]) }
        val created = receipts.import(inputs, "long") as ImportOutcome.Created
        receipts.runOcr(created.receiptId, dayFirst = false)
        val detail = receipts.observeDetail(created.receiptId).first()!!
        val r = detail.review
        assertEquals(PDF_TEXT_ENGINE, t.db.receiptDao().getById(created.receiptId)!!.ocrEngine)
        assertTrue("exact text", detail.ocrLines.all { it.confidence == 100 })
        assertEquals(prices, r.items.map { it.lineTotal.cents })
        assertEquals(Money(prices.sum()), r.total)
        assertTrue(ReceiptMath.reconcile(r).isEmpty())
        // Reading again uses the same text: still every item exactly once.
        receipts.runOcr(created.receiptId, dayFirst = false)
        assertEquals(60L, t.count("SELECT COUNT(*) FROM receipt_items"))
    }

    @Test
    fun scannedPdfsHaveNoTextAndAreReadByOcr() = runTest(timeout = 5.minutes) {
        // A PDF made from a photo of a receipt (as scanner apps produce): only an image.
        val photo = ReceiptPhotos.render(ReceiptPhotos.GROCERY, ReceiptPhotos.Style(skewDegrees = 1.5f))
        val bitmap = BitmapFactory.decodeByteArray(photo, 0, photo.size)
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(bitmap.width / 3, bitmap.height / 3, 1).create())
        page.canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, bitmap.width / 3, bitmap.height / 3), null)
        document.finishPage(page)
        val file = File(dir, "scan.pdf").also { out -> out.outputStream().use(document::writeTo); document.close() }

        val pages = render(file)
        assertTrue(pages.text.all { it == null })
        val created = receipts.import(pages.images.mapIndexed { i, image -> PageInput(image, embeddedText = pages.text[i]) }, "scan") as ImportOutcome.Created
        receipts.runOcr(created.receiptId, dayFirst = false)
        val r = receipts.observeDetail(created.receiptId).first()!!.review
        assertEquals(6, r.items.size)
        assertEquals(Money(2_148), r.total)
        assertEquals(TesseractOcrEngine(context).name, t.db.receiptDao().getById(created.receiptId)!!.ocrEngine)
    }
}
