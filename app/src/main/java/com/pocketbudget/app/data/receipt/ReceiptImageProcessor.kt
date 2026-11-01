package com.pocketbudget.app.data.receipt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.pocketbudget.app.domain.model.PageInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

class ReceiptImageException(message: String) : IOException(message)

/** A page ready for encrypted storage: a fresh JPEG with no metadata. */
class ProcessedPage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val sourceSha256: String,
    val perceptualHash: Long,
)

/**
 * Normalises receipt photos. The output is re-encoded from pixels, so EXIF data
 * (location, device, time) in the source never reaches storage. Size is capped at a
 * resolution that keeps small receipt print readable.
 */
object ReceiptImageProcessor {

    const val MAX_LONG_SIDE = 3000
    const val MAX_INPUT_BYTES = 40 * 1024 * 1024
    private const val JPEG_QUALITY = 90

    fun process(input: PageInput): ProcessedPage {
        if (input.bytes.isEmpty() || input.bytes.size > MAX_INPUT_BYTES) {
            throw ReceiptImageException("This image is empty or too large")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ReceiptImageException("This file is not a supported image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LONG_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            input.bytes,
            0,
            input.bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw ReceiptImageException("This file is not a supported image")

        val oriented = transform(decoded, orientationMatrix(input.bytes, input.rotation))
        val cropped = input.crop?.let { crop ->
            val left = (crop.left * oriented.width).roundToInt().coerceIn(0, oriented.width - 1)
            val top = (crop.top * oriented.height).roundToInt().coerceIn(0, oriented.height - 1)
            val right = (crop.right * oriented.width).roundToInt().coerceIn(left + 1, oriented.width)
            val bottom = (crop.bottom * oriented.height).roundToInt().coerceIn(top + 1, oriented.height)
            Bitmap.createBitmap(oriented, left, top, right - left, bottom - top)
        } ?: oriented
        val scaled = scaleDown(cropped, MAX_LONG_SIDE)
        val finished = if (input.enhance) enhance(scaled) else scaled

        val out = ByteArrayOutputStream()
        finished.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return ProcessedPage(
            jpeg = out.toByteArray(),
            width = finished.width,
            height = finished.height,
            sourceSha256 = ReceiptImageStore.sha256(input.bytes),
            perceptualHash = differenceHash(finished),
        )
    }

    /** Grayscale, contrast-stretched copy that Tesseract reads more reliably. */
    fun forOcr(jpeg: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw ReceiptImageException("Receipt image could not be decoded")
        return enhance(bitmap)
    }

    /** Small upright copy of an unsaved photo for the capture screen; crop fractions refer to it. */
    fun preview(bytes: ByteArray, maxLongSide: Int): Bitmap {
        val decoded = decode(bytes, maxLongSide) ?: throw ReceiptImageException("This file is not a supported image")
        return transform(decoded, orientationMatrix(bytes, 0))
    }

    /** Whether the photo already carries an EXIF rotation, so a camera's own rotation must not be added again. */
    fun hasExifRotation(bytes: ByteArray): Boolean = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) !in
            setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED)
    } catch (_: IOException) {
        false
    }

    /** [bitmap] turned clockwise by [degrees] (a multiple of 90). */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        transform(bitmap, Matrix().apply { if (degrees % 360 != 0) postRotate(degrees.toFloat()) })

    fun decode(jpeg: ByteArray, maxLongSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Photo orientation from EXIF (read only, never copied) followed by the user's rotation. */
    private fun orientationMatrix(bytes: ByteArray, userRotation: Int): Matrix {
        val matrix = Matrix()
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        val extra = ((userRotation % 360) + 360) % 360
        require(extra % 90 == 0) { "Rotation must be a multiple of 90 degrees" }
        if (extra != 0) matrix.postRotate(extra.toFloat())
        return matrix
    }

    private fun transform(bitmap: Bitmap, matrix: Matrix): Bitmap =
        if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

    private fun scaleDown(bitmap: Bitmap, maxLongSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= maxLongSide) return bitmap
        val ratio = maxLongSide.toFloat() / longSide
        return bitmap.scale((bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1))
    }

    /** Grayscale, then stretch the 1st-99th percentile of brightness to full range. */
    private fun enhance(source: Bitmap): Bitmap {
        val gray = createBitmap(source.width, source.height)
        Canvas(gray).drawBitmap(
            source,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) },
        )
        val sample = gray.scale(64, max(1, 64 * gray.height / max(1, gray.width)))
        val histogram = IntArray(256)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        pixels.forEach { histogram[it and 0xFF]++ }
        val low = percentile(histogram, pixels.size, 1)
        val high = percentile(histogram, pixels.size, 99)
        if (high - low < 16) return gray
        val scale = 255f / (high - low)
        val stretch = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, -low * scale,
                0f, scale, 0f, 0f, -low * scale,
                0f, 0f, scale, 0f, -low * scale,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val result = createBitmap(gray.width, gray.height)
        Canvas(result).drawBitmap(gray, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(stretch) })
        return result
    }

    private fun percentile(histogram: IntArray, count: Int, percent: Int): Int {
        val target = count * percent / 100
        var seen = 0
        histogram.forEachIndexed { value, n ->
            seen += n
            if (seen > target) return value
        }
        return 255
    }

    /** 64-bit dHash: brightness gradients of a 9x8 thumbnail, robust to re-compression and small shifts. */
    fun differenceHash(bitmap: Bitmap): Long {
        val small = bitmap.scale(9, 8)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                hash = (hash shl 1) or (if (luma(small[x, y]) > luma(small[x + 1, y])) 1L else 0L)
            }
        }
        return hash
    }

    private fun luma(color: Int): Int =
        (((color shr 16) and 0xFF) * 299 + ((color shr 8) and 0xFF) * 587 + (color and 0xFF) * 114) / 1000
}
