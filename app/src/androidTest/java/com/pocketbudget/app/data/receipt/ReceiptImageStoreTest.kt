package com.pocketbudget.app.data.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketbudget.app.domain.model.CropRect
import com.pocketbudget.app.domain.model.PageInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class ReceiptImageStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "image-store-test").also { it.deleteRecursively() }
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val store = ReceiptImageStore(dir, { key })

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun jpeg(width: Int = 400, height: Int = 800): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (x in 0 until width / 2) bitmap.setPixel(x, 10, Color.BLACK)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    @Test
    fun imagesAreEncryptedAtRestAndRoundTrip() = runTest {
        val plain = jpeg()
        val stored = store.write(plain)
        val onDisk = File(dir, stored.fileName).readBytes()
        assertFalse("no JPEG header on disk", onDisk.size > 2 && onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte())
        assertFalse(onDisk.toList().windowed(16).any { it.toByteArray().contentEquals(plain.copyOfRange(100, 116)) })
        assertArrayEquals(plain, store.read(stored.fileName, stored.contentSha256))
        assertTrue("only app-private storage", File(dir, stored.fileName).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }

    @Test
    fun tamperedSwappedOrForeignFilesAreRejected() = runTest {
        val a = store.write(jpeg())
        val b = store.write(jpeg(300, 300))
        val fileA = File(dir, a.fileName)

        // Flip one ciphertext byte.
        val bytes = fileA.readBytes().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        fileA.writeBytes(bytes)
        expectUnreadable { store.read(a.fileName, a.contentSha256) }

        // Another record's file under this name fails authentication (the name is bound in).
        File(dir, b.fileName).copyTo(fileA, overwrite = true)
        expectUnreadable { store.read(a.fileName, a.contentSha256) }

        // A different key cannot read it.
        val other = ReceiptImageStore(dir, { ByteArray(32).also(SecureRandom()::nextBytes) })
        expectUnreadable { other.read(b.fileName, b.contentSha256) }
        // Missing file.
        File(dir, b.fileName).delete()
        expectUnreadable { store.read(b.fileName, b.contentSha256) }
    }

    private suspend fun expectUnreadable(block: suspend () -> Unit) {
        try {
            block()
            fail("expected ReceiptImageUnreadableException")
        } catch (_: ReceiptImageUnreadableException) {
        }
    }

    @Test
    fun metadataIsRemovedAndOrientationApplied() {
        // A portrait photo stored sideways with EXIF orientation and GPS, as cameras write it.
        val file = File(context.cacheDir, "exif-test.jpg").apply { writeBytes(jpeg(800, 400)) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "43/1,39/1,1152/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "79/1,22/1,5952/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            saveAttributes()
        }
        val source = file.readBytes().also { file.delete() }
        assertTrue(ExifInterface(ByteArrayInputStream(source)).getLatLong(FloatArray(2)))

        val page = ReceiptImageProcessor.process(PageInput(source))
        val exif = ExifInterface(ByteArrayInputStream(page.jpeg))
        assertFalse(exif.getLatLong(FloatArray(2)))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(400 to 800, page.width to page.height)
        val decoded = BitmapFactory.decodeByteArray(page.jpeg, 0, page.jpeg.size)
        assertEquals(400 to 800, decoded.width to decoded.height)
    }

    @Test
    fun cropRotateEnhanceAndSizeLimit() {
        val rotated = ReceiptImageProcessor.process(PageInput(jpeg(400, 800), rotation = 90))
        assertEquals(800 to 400, rotated.width to rotated.height)
        val cropped = ReceiptImageProcessor.process(PageInput(jpeg(400, 800), crop = CropRect(0.25f, 0f, 0.75f, 0.5f), enhance = true))
        assertEquals(200 to 400, cropped.width to cropped.height)
        val huge = ReceiptImageProcessor.process(PageInput(jpeg(2000, 7000)))
        assertTrue(maxOf(huge.width, huge.height) <= ReceiptImageProcessor.MAX_LONG_SIDE)
        // Receipt proportions are preserved, so print stays readable.
        assertEquals(2000.0 / 7000, huge.width.toDouble() / huge.height, 0.01)
        try {
            ReceiptImageProcessor.process(PageInput(byteArrayOf(1, 2, 3)))
            fail("not an image")
        } catch (_: ReceiptImageException) {
        }
    }

    @Test
    fun orphanCleanupKeepsReferencedAndRecentFiles() = runTest {
        val referenced = store.write(jpeg())
        val orphan = store.write(jpeg())
        val recent = store.write(jpeg())
        File(dir, referenced.fileName).setLastModified(0)
        File(dir, orphan.fileName).setLastModified(0)
        assertEquals(1, store.removeOrphans(setOf(referenced.fileName)))
        assertTrue(File(dir, referenced.fileName).exists())
        assertTrue(File(dir, recent.fileName).exists())
        assertFalse(File(dir, orphan.fileName).exists())
    }
}
