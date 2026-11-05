package com.cloakedcent.app.data.receipt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Photo-like receipt images for OCR tests: thermal-style print on off-white paper,
 * photographed on a darker table, slightly rotated, with uneven lighting, a soft
 * shadow, sensor noise and JPEG compression. Rendered on the device, so no binary
 * fixtures are needed.
 */
object ReceiptPhotos {

    data class Style(
        val skewDegrees: Float = 2.5f,
        val shadow: Boolean = true,
        val noise: Int = 14,
        val jpegQuality: Int = 80,
        val textSize: Float = 30f,
        val seed: Int = 7,
    )

    fun render(lines: List<String>, style: Style = Style()): ByteArray {
        val random = Random(style.seed)
        val lineHeight = style.textSize * 1.45f
        val paperWidth = (style.textSize * 0.62f * 34 + 120).toInt()
        val paperHeight = (lineHeight * lines.size + 160).toInt()

        // The receipt itself: off-white thermal paper with slightly faded dark grey print.
        val paper = Bitmap.createBitmap(paperWidth, paperHeight, Bitmap.Config.ARGB_8888)
        Canvas(paper).apply {
            drawColor(Color.rgb(246, 243, 236))
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = style.textSize
            }
            lines.forEachIndexed { i, line ->
                val shade = 30 + random.nextInt(40)
                ink.color = Color.rgb(shade, shade, shade + 5)
                drawText(line, 60f, 90f + i * lineHeight, ink)
            }
        }

        // The photo: a table, the receipt rotated on it, lighting falling off to one side.
        val width = (paperWidth * 1.25f).toInt()
        val height = (paperHeight * 1.12f).toInt()
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(92, 78, 64))
            save()
            rotate(style.skewDegrees, width / 2f, height / 2f)
            drawBitmap(paper, (width - paperWidth) / 2f, (height - paperHeight) / 2f, null)
            restore()
            if (style.shadow) {
                drawRect(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    Paint().apply {
                        shader = LinearGradient(0f, 0f, width.toFloat(), height * 0.6f, Color.argb(0, 0, 0, 0), Color.argb(70, 0, 0, 0), Shader.TileMode.CLAMP)
                    },
                )
                drawCircle(
                    width * 0.8f, height * 0.25f, width * 0.5f,
                    Paint().apply { shader = RadialGradient(width * 0.8f, height * 0.25f, width * 0.5f, Color.argb(45, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP) },
                )
            }
        }
        if (style.noise > 0) {
            val pixels = IntArray(width * height)
            photo.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val n = random.nextInt(-style.noise, style.noise + 1)
                val p = pixels[i]
                pixels[i] = Color.rgb(
                    (Color.red(p) + n).coerceIn(0, 255),
                    (Color.green(p) + n).coerceIn(0, 255),
                    (Color.blue(p) + n).coerceIn(0, 255),
                )
            }
            photo.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, style.jpegQuality, it) }.toByteArray()
    }

    /** A supermarket receipt with codes, tax flags, a weighed item, multi-buy and a coupon. */
    val GROCERY = listOf(
        "FRESH MART #0412",
        "123 Main St",
        "Springfield, IL 62701",
        "11/14/2026  10:15 AM",
        "",
        "MILK 2% 1L         3.49 F",
        "BREAD WHOLE WHEAT  2.79 F",
        "BANANAS",
        "  1.25 kg @ 1.74/kg   2.18 F",
        "YOGURT GREEK",
        "  3 @ 1.29            3.87 F",
        "DISH SOAP          5.00 T",
        "  COUPON          -0.50",
        "EGGS LARGE 12      4.29 F",
        "",
        "SUBTOTAL          21.12",
        "TAX                0.36",
        "TOTAL             21.48",
        "VISA              21.48",
        "THANK YOU",
    )

    /** A pharmacy-style receipt with item codes before descriptions and a receipt-level discount. */
    val PHARMACY = listOf(
        "CORNER PHARMACY",
        "45 King Street West",
        "Receipt #: 88213",
        "2026-11-12 18:05",
        "",
        "0001234567 VITAMIN C 500MG   8.99",
        "0004567891 HAND SOAP         3.50",
        "0007654321 TISSUES 2 PK      4.20",
        "",
        "Subtotal                    16.69",
        "Member Discount             -1.00",
        "HST                          2.04",
        "Total                       17.73",
        "DEBIT                       17.73",
    )
}
