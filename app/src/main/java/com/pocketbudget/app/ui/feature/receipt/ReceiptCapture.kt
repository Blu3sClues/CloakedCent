package com.pocketbudget.app.ui.feature.receipt

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketbudget.app.PocketBudgetApp
import com.pocketbudget.app.R
import com.pocketbudget.app.data.receipt.PdfImportException
import com.pocketbudget.app.data.receipt.PdfPages
import com.pocketbudget.app.data.receipt.PdfReceiptRenderer
import com.pocketbudget.app.data.receipt.ReceiptImageProcessor
import com.pocketbudget.app.domain.model.CropRect
import com.pocketbudget.app.domain.model.ImportOutcome
import com.pocketbudget.app.domain.model.OcrLine
import com.pocketbudget.app.domain.model.PageInput
import com.pocketbudget.app.domain.repository.ReceiptRepository
import com.pocketbudget.app.ui.components.BackTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A page held in memory until the receipt is stored; never written to disk unencrypted. */
class PendingPage(
    val key: Long,
    val bytes: ByteArray,
    /** Upright, reduced copy for display; crop fractions refer to it after [rotation]. */
    val preview: Bitmap,
    val rotation: Int = 0,
    val crop: CropRect? = null,
    val enhance: Boolean = false,
    /** Text printed in a digital PDF page; dropped once the page is rotated or cropped. */
    val embeddedText: List<OcrLine>? = null,
) {
    fun copy(rotation: Int = this.rotation, crop: CropRect? = this.crop, enhance: Boolean = this.enhance) =
        PendingPage(
            key, bytes, preview, rotation, crop, enhance,
            embeddedText.takeIf { rotation == this.rotation && crop == this.crop },
        )
}

data class CaptureUiState(
    val pages: List<PendingPage> = emptyList(),
    val busy: Boolean = false,
    val error: Int? = null,
    /** Information that is not an error, e.g. pages left out of a long PDF. */
    val notice: Int? = null,
    val duplicateOf: Long? = null,
    val createdId: Long? = null,
)

class CaptureViewModel(
    private val receipts: ReceiptRepository,
    private val readUri: suspend (Uri) -> ByteArray,
    private val readPdf: suspend (Uri) -> PdfPages = { PdfPages(emptyList(), false) },
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    // One id per capture session: tapping "Read receipt" twice stores one receipt.
    private val requestId = "capture-" + UUID.randomUUID()
    private var nextKey = 0L

    /** [cameraRotation] is applied only if the photo has no EXIF orientation of its own. */
    fun addPhoto(bytes: ByteArray, cameraRotation: Int = 0, embeddedText: List<OcrLine>? = null) = viewModelScope.launch {
        try {
            val page = withContext(Dispatchers.Default) {
                val rotation = if (ReceiptImageProcessor.hasExifRotation(bytes)) 0 else cameraRotation
                PendingPage(nextKey++, bytes, ReceiptImageProcessor.preview(bytes, PREVIEW_SIZE), rotation, embeddedText = embeddedText)
            }
            _state.update { it.copy(pages = it.pages + page, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(error = R.string.capture_image_error) }
        }
    }

    fun addUris(uris: List<Uri>) = viewModelScope.launch {
        uris.forEach { uri ->
            try {
                addPhoto(withContext(Dispatchers.IO) { readUri(uri) }).join()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = R.string.capture_image_error) }
            }
        }
    }

    /** Each PDF page (or slice of a long page) becomes a receipt page, in order. */
    fun addPdf(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { readPdf(uri) }
                pdf.images.forEachIndexed { i, image -> addPhoto(image, embeddedText = pdf.text.getOrNull(i)).join() }
                _state.update {
                    it.copy(busy = false, notice = if (pdf.truncated) R.string.capture_pdf_truncated else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when ((e as? PdfImportException)?.reason) {
                    PdfImportException.Reason.PASSWORD_PROTECTED -> R.string.capture_pdf_protected
                    PdfImportException.Reason.EMPTY -> R.string.capture_pdf_empty
                    else -> R.string.capture_pdf_error
                }
                _state.update { it.copy(busy = false, error = message) }
            }
        }
    }

    fun updatePage(key: Long, change: (PendingPage) -> PendingPage) =
        _state.update { s -> s.copy(pages = s.pages.map { if (it.key == key) change(it) else it }) }

    fun removePage(key: Long) = _state.update { s -> s.copy(pages = s.pages.filterNot { it.key == key }) }

    fun cameraError() = _state.update { it.copy(error = R.string.capture_camera_unavailable) }

    fun dismissDuplicate() = _state.update { it.copy(duplicateOf = null) }

    fun import(allowDuplicate: Boolean = false) {
        val pages = _state.value.pages
        if (pages.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null, duplicateOf = null) }
        viewModelScope.launch {
            try {
                val inputs = pages.map { PageInput(it.bytes, it.rotation, it.crop, it.enhance, it.embeddedText) }
                when (val outcome = receipts.import(inputs, requestId, allowDuplicate)) {
                    is ImportOutcome.Created -> _state.update { it.copy(busy = false, createdId = outcome.receiptId) }
                    is ImportOutcome.Duplicate -> _state.update { it.copy(busy = false, duplicateOf = outcome.existingReceiptId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = R.string.capture_image_error) }
            }
        }
    }

    companion object {
        private const val PREVIEW_SIZE = 1600

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PocketBudgetApp
                CaptureViewModel(
                    app.container.receiptRepository,
                    readUri = { uri ->
                        val stream = app.contentResolver.openInputStream(uri) ?: error("Unreadable")
                        stream.use { input ->
                            // Bounded read: refuse anything larger than an image could reasonably be.
                            val bytes = input.readNBytesCompat(ReceiptImageProcessor.MAX_INPUT_BYTES + 1)
                            require(bytes.size <= ReceiptImageProcessor.MAX_INPUT_BYTES) { "Too large" }
                            bytes
                        }
                    },
                    readPdf = { uri -> PdfReceiptRenderer.render(app, uri) },
                )
            }
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

@Composable
fun ReceiptCaptureRoute(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    onOpenExisting: (Long) -> Unit,
    viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var cameraOpen by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        cameraOpen = granted
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) viewModel.addUris(uris)
    }
    // The system document picker: read-only access to the one file chosen, no storage permission.
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addPdf(uri)
    }

    if (cameraOpen) {
        CameraCapture(
            pageCount = state.pages.size,
            onCaptured = { bytes, rotation -> viewModel.addPhoto(bytes, rotation) },
            onClose = { cameraOpen = false },
            onError = {
                cameraOpen = false
                viewModel.cameraError()
            },
        )
        return
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.capture_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.capture_hint), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) cameraOpen = true else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    enabled = !state.busy,
                ) {
                    Icon(painterResource(R.drawable.ic_camera), null)
                    Text(stringResource(R.string.capture_take_photo), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.busy,
                ) {
                    Icon(painterResource(R.drawable.ic_photo), null)
                    Text(stringResource(R.string.capture_choose_photos), Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, enabled = !state.busy) {
                Icon(painterResource(R.drawable.ic_receipt), null)
                Text(stringResource(R.string.capture_import_pdf), Modifier.padding(start = 8.dp))
            }
            if (cameraDenied) Text(stringResource(R.string.capture_camera_denied), color = MaterialTheme.colorScheme.error)
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }

            if (state.pages.isNotEmpty()) {
                Text(stringResource(R.string.capture_pages, state.pages.size), style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    itemsIndexed(state.pages, key = { _, page -> page.key }) { index, page ->
                        PageThumbnail(page, index, onClick = { editing = page.key })
                    }
                }
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.capture_importing))
                    }
                } else {
                    Button(onClick = { viewModel.import() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.capture_scan))
                    }
                }
            }
        }
    }

    state.pages.firstOrNull { it.key == editing }?.let { page ->
        PageEditor(
            page = page,
            number = state.pages.indexOf(page) + 1,
            onChange = { change -> viewModel.updatePage(page.key, change) },
            onRemove = {
                viewModel.removePage(page.key)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    state.duplicateOf?.let { existing ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text(stringResource(R.string.duplicate_title)) },
            text = { Text(stringResource(R.string.duplicate_body)) },
            confirmButton = { TextButton(onClick = { viewModel.dismissDuplicate(); onOpenExisting(existing) }) { Text(stringResource(R.string.duplicate_open)) } },
            dismissButton = { TextButton(onClick = { viewModel.import(allowDuplicate = true) }) { Text(stringResource(R.string.duplicate_import_anyway)) } },
        )
    }
}

private const val MAX_PICK = 10

@Composable
private fun PageThumbnail(page: PendingPage, index: Int, onClick: () -> Unit) {
    val rotated = remember(page.preview, page.rotation) { ReceiptImageProcessor.rotate(page.preview, page.rotation).asImageBitmap() }
    val label = stringResource(R.string.review_page_description, index + 1)
    Card(Modifier.clickable(onClick = onClick).semantics { contentDescription = label }) {
        Box {
            Image(rotated, null, Modifier.height(320.dp).aspectRatio(rotated.width.toFloat() / rotated.height), contentScale = ContentScale.Fit)
            Text(
                "${index + 1}",
                Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Grayscale with extra contrast: a quick preview of "Enhance faded print". */
private val enhancePreview = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(0f) }.also { m ->
        m.timesAssign(ColorMatrix(floatArrayOf(1.4f, 0f, 0f, 0f, -50f, 0f, 1.4f, 0f, 0f, -50f, 0f, 0f, 1.4f, 0f, -50f, 0f, 0f, 0f, 1f, 0f)))
    },
)

@Composable
private fun PageEditor(
    page: PendingPage,
    number: Int,
    onChange: ((PendingPage) -> PendingPage) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxSize().padding(12.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.page_edit_title, number), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.page_crop_hint), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CropEditor(page, onCropChange = { crop -> onChange { it.copy(crop = crop) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onChange { it.copy(rotation = (it.rotation + 270) % 360, crop = null) } }) {
                        Icon(painterResource(R.drawable.ic_rotate_left), stringResource(R.string.page_rotate_left))
                    }
                    IconButton(onClick = { onChange { it.copy(rotation = (it.rotation + 90) % 360, crop = null) } }) {
                        Icon(painterResource(R.drawable.ic_rotate_right), stringResource(R.string.page_rotate_right))
                    }
                    IconButton(onClick = { onChange { it.copy(crop = null) } }) {
                        Icon(painterResource(R.drawable.ic_crop), stringResource(R.string.page_reset_crop))
                    }
                    IconButton(onClick = onRemove) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.page_remove))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.page_enhance), Modifier.weight(1f))
                    Switch(checked = page.enhance, onCheckedChange = { on -> onChange { it.copy(enhance = on) } })
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}

/** The page with a draggable crop rectangle; corners move independently. */
@Composable
private fun CropEditor(page: PendingPage, onCropChange: (CropRect) -> Unit) {
    val image = remember(page.preview, page.rotation) { ReceiptImageProcessor.rotate(page.preview, page.rotation).asImageBitmap() }
    val crop by rememberUpdatedState(page.crop ?: CropRect.FULL)
    val onChange by rememberUpdatedState(onCropChange)
    val outline = MaterialTheme.colorScheme.primary
    Box(Modifier.aspectRatio(image.width.toFloat() / image.height, matchHeightConstraintsFirst = image.height > image.width)) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = if (page.enhance) enhancePreview else null)
        Canvas(
            Modifier.fillMaxSize().pointerInput(page.key, page.rotation) {
                var corner = -1
                detectDragGestures(
                    onDragStart = { start ->
                        val c = crop
                        val corners = listOf(
                            Offset(c.left * size.width, c.top * size.height),
                            Offset(c.right * size.width, c.top * size.height),
                            Offset(c.left * size.width, c.bottom * size.height),
                            Offset(c.right * size.width, c.bottom * size.height),
                        )
                        corner = corners.indices.minBy { (corners[it] - start).getDistance() }
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val c = crop
                        val dx = drag.x / size.width
                        val dy = drag.y / size.height
                        val min = 0.05f
                        var (l, t, r, b) = listOf(c.left, c.top, c.right, c.bottom)
                        if (corner == 0 || corner == 2) l = (l + dx).coerceIn(0f, r - min)
                        if (corner == 1 || corner == 3) r = (r + dx).coerceIn(l + min, 1f)
                        if (corner == 0 || corner == 1) t = (t + dy).coerceIn(0f, b - min)
                        if (corner == 2 || corner == 3) b = (b + dy).coerceIn(t + min, 1f)
                        onChange(CropRect(l, t, r, b))
                    },
                )
            },
        ) {
            val c = crop
            val topLeft = Offset(c.left * size.width, c.top * size.height)
            val rect = Size((c.right - c.left) * size.width, (c.bottom - c.top) * size.height)
            // Dim what will be cut away.
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset.Zero, Size(size.width, topLeft.y))
            drawRect(shade, Offset(0f, topLeft.y + rect.height), Size(size.width, size.height - topLeft.y - rect.height))
            drawRect(shade, Offset(0f, topLeft.y), Size(topLeft.x, rect.height))
            drawRect(shade, Offset(topLeft.x + rect.width, topLeft.y), Size(size.width - topLeft.x - rect.width, rect.height))
            drawRect(outline, topLeft, rect, style = Stroke(width = 3.dp.toPx()))
            listOf(topLeft, Offset(topLeft.x + rect.width, topLeft.y), Offset(topLeft.x, topLeft.y + rect.height), Offset(topLeft.x + rect.width, topLeft.y + rect.height))
                .forEach { drawCircle(outline, radius = 10.dp.toPx(), center = it) }
        }
    }
}

/** Full-screen CameraX preview; each capture is kept in memory as JPEG bytes. */
@Composable
private fun CameraCapture(
    pageCount: Int,
    onCaptured: (ByteArray, Int) -> Unit,
    onClose: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val onErrorState by rememberUpdatedState(onError)

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onErrorState()
        } finally {
            provider.unbindAll()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.capture_close_camera), tint = Color.White)
            }
            FilledIconButton(
                onClick = {
                    capturing = true
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val rotation = image.imageInfo.rotationDegrees
                                val bytes = image.use {
                                    val buffer = it.planes[0].buffer
                                    ByteArray(buffer.remaining()).also(buffer::get)
                                }
                                capturing = false
                                onCaptured(bytes, rotation)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                capturing = false
                                onErrorState()
                            }
                        },
                    )
                },
                enabled = !capturing,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(painterResource(R.drawable.ic_camera), stringResource(R.string.capture_shutter))
            }
            Text(stringResource(R.string.capture_pages, pageCount), color = Color.White)
        }
    }
}
