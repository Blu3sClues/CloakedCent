package com.cloakedcent.app.data.receipt

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import com.cloakedcent.app.domain.model.OcrBox
import com.cloakedcent.app.domain.model.OcrLayout
import com.cloakedcent.app.domain.model.OcrLine
import com.cloakedcent.app.domain.model.OcrWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Recognises text on one stored page. Implementations must work entirely offline. */
interface OcrEngine {
    /** Recorded with each receipt so results can be re-read when the engine changes. */
    val name: String

    suspend fun recognize(page: Int, jpeg: ByteArray): List<OcrLine>
}

/**
 * Tesseract 5 (via Tesseract4Android) with the English "fast" LSTM model bundled in
 * the APK. The model is copied once to app-private storage and its SHA-256 checked;
 * nothing is ever downloaded.
 */
class TesseractOcrEngine(private val context: Context) : OcrEngine {

    override val name = "tesseract-5.5.1/eng-fast"

    // TessBaseAPI is not thread-safe, and one recognition at a time keeps memory bounded.
    private val lock = Mutex()

    override suspend fun recognize(page: Int, jpeg: ByteArray): List<OcrLine> = withContext(Dispatchers.Default) {
        lock.withLock {
            val dataPath = ensureModel()
            val bitmap = ReceiptImageProcessor.forOcr(jpeg)
            val api = TessBaseAPI()
            try {
                if (!api.init(dataPath.path, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) {
                    throw IOException("OCR model could not be loaded")
                }
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                // Bitmaps carry no resolution; receipts photographed at reading distance are roughly 300 DPI.
                api.setVariable("user_defined_dpi", "300")
                api.setImage(bitmap)
                api.getUTF8Text() // runs recognition
                coroutineContext.ensureActive()
                val words = mutableListOf<OcrWord>()
                val iterator = api.resultIterator ?: return@withLock emptyList()
                try {
                    iterator.begin()
                    val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                    do {
                        val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            val rect = iterator.getBoundingRect(level)
                            words += OcrWord(
                                text = text,
                                confidence = iterator.confidence(level).toInt().coerceIn(0, 100),
                                box = OcrBox(rect.left, rect.top, rect.right, rect.bottom),
                            )
                        }
                    } while (iterator.next(level))
                } finally {
                    iterator.delete()
                }
                coroutineContext.ensureActive()
                // Names and prices may be read as separate columns; rebuild printed rows.
                OcrLayout.rows(page, rereadAmounts(api, bitmap.width, bitmap.height, words))
            } finally {
                api.recycle()
                bitmap.recycle()
            }
        }
    }

    /**
     * Second look at price-like words that did not read as an amount (e.g. "2.79" read
     * as "29", a dropped decimal point being the most common failure). Each is re-read
     * alone, limited to digits and separators. The result is used only if it is a
     * well-formed amount; the user still verifies every value.
     */
    private fun rereadAmounts(api: TessBaseAPI, width: Int, height: Int, words: List<OcrWord>): List<OcrWord> {
        val suspicious = words.filter { word ->
            word.box.left > width / 3 && word.text.any(Char::isDigit) && !AMOUNT.matches(word.text) &&
                word.text.count(Char::isLetter) <= 1 && !word.text.contains(':') && !word.text.contains('@')
        }
        if (suspicious.isEmpty()) return words
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789.,-$")
        val replacements = suspicious.associateWith { word ->
            val padX = (word.box.bottom - word.box.top) / 2
            val padY = (word.box.bottom - word.box.top) / 4
            // Price columns are right-aligned, so include the space to the left where a lost "2." may sit.
            val left = (word.box.left - 3 * padX).coerceAtLeast(0)
            api.setRectangle(left, (word.box.top - padY).coerceAtLeast(0), (word.box.right + padX).coerceAtMost(width) - left, (word.box.bottom - word.box.top + 2 * padY).coerceAtMost(height))
            val text = api.getUTF8Text()?.trim()?.replace(" ", "").orEmpty()
            if (AMOUNT.matches(text)) word.copy(text = text, confidence = minOf(word.confidence, api.meanConfidence())) else word
        }
        return words.map { replacements[it] ?: it }
    }

    /** Returns the directory containing tessdata/, copying and verifying the model if needed. */
    private fun ensureModel(): File {
        verifiedModelDir?.let { return it }
        val base = File(context.noBackupFilesDir, "ocr")
        val model = File(base, "tessdata/$LANGUAGE.traineddata")
        if (model.isFile && sha256(model.readBytes()) == MODEL_SHA256) return base.also { verifiedModelDir = it }
        model.parentFile!!.mkdirs()
        val temp = File(model.path + ".tmp")
        val bytes = context.assets.open("tessdata/$LANGUAGE.traineddata").use { it.readBytes() }
        if (sha256(bytes) != MODEL_SHA256) throw IOException("Bundled OCR model failed its integrity check")
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(model)) throw IOException("OCR model could not be installed")
        return base.also { verifiedModelDir = it }
    }

    // Checked once per process; only touched while holding [lock].
    private var verifiedModelDir: File? = null

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LANGUAGE = "eng"
        val AMOUNT = Regex("""-?\$?\d{1,6}[.,]\d{2}-?""")

        /** tessdata_fast eng.traineddata (Apache-2.0), as bundled in src/main/assets. */
        const val MODEL_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    }
}
