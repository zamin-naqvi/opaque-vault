package app.opaquevault.ui

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.opaquevault.VaultController
import app.opaquevault.vault.VaultEntry
import app.opaquevault.vault.isVideo
import app.opaquevault.vault.isViewable
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

@Composable
fun ViewerScreen(
    entries: List<VaultEntry>,
    startId: String,
    controller: VaultController,
    allowInsecureQaScreenshots: Boolean = false,
    onDelete: (VaultEntry) -> Unit,
    onExport: (VaultEntry) -> Unit,
    onClose: () -> Unit,
) {
    val media = remember(entries) { entries.filter { it.isViewable() } }
    if (media.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val startIndex = remember(startId, media) { media.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { media.size }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomedPage by remember { mutableIntStateOf(-1) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val currentPage = pagerState.currentPage.coerceIn(0, media.lastIndex)
    val current = media[currentPage]

    fun requestDelete(entry: VaultEntry) {
        if (controller.state.settings.confirmVaultDeletion) confirmDelete = true else onDelete(entry)
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Swiping always works, even while a video plays; only a zoomed photo holds the pager.
            userScrollEnabled = zoomedPage != currentPage,
            beyondViewportPageCount = 1,
            key = { media[it].id },
        ) { page ->
            val entry = media[page]
            if (entry.isVideo()) {
                VaultVideoPlayer(
                    entry = entry,
                    controller = controller,
                    active = page == currentPage,
                    allowInsecureQaScreenshots = allowInsecureQaScreenshots,
                    onClose = onClose,
                    onInfo = { showInfo = true },
                    onDelete = { requestDelete(entry) },
                    onExport = { onExport(entry) },
                )
            } else {
                val loaded by produceState<VaultController.LoadedImage?>(initialValue = null, entry.id) {
                    value = controller.loadImage(entry.id)
                }
                when (val image = loaded) {
                    null -> LoadingPage(controller.cachedThumbnail(entry.id))
                    is VaultController.LoadedImage.Failed -> FailedPage(image.reason)
                    is VaultController.LoadedImage.Ready -> {
                        if (image.bitmap.isRecycled) {
                            FailedPage("The vault locked.")
                        } else {
                            ZoomableImage(
                                bitmap = remember(image.bitmap) { image.bitmap.asImageBitmap() },
                                contentDescription = entry.displayName,
                                onTap = { chromeVisible = !chromeVisible },
                                onZoomChanged = { zoomed ->
                                    zoomedPage = when {
                                        zoomed -> page
                                        zoomedPage == page -> -1
                                        else -> zoomedPage
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Photo chrome. Videos draw their own controls.
        if (!current.isVideo()) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        current.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onExport(current) }) {
                        Icon(Icons.Rounded.Download, contentDescription = "Export", tint = Color.White)
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Rounded.Info, contentDescription = "Photo details", tint = Color.White)
                    }
                    IconButton(onClick = { requestDelete(current) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete from vault", tint = Color.White)
                    }
                }
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                ) {
                    Text(
                        "${currentPage + 1} / ${media.size}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showInfo) MediaInfoDialog(current, onDismiss = { showInfo = false })
    if (confirmDelete) {
        val noun = if (current.isVideo()) "video" else "photo"
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text("Delete this $noun from the vault?") },
            text = { Text("Its encryption key is destroyed first, then its ciphertext. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete(current)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Pinch to zoom (1×–6×), drag to pan within the image bounds, double-tap to zoom in or reset.
 * Single-finger drags are left to the pager while not zoomed, so swiping between items still works.
 */
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(candidate: Offset, atScale: Float): Offset {
        val fit = if (size.width == 0 || size.height == 0) 1f else min(
            size.width / bitmap.width.toFloat(),
            size.height / bitmap.height.toFloat(),
        )
        val drawnWidth = bitmap.width * fit * atScale
        val drawnHeight = bitmap.height * fit * atScale
        val maxX = max(0f, (drawnWidth - size.width) / 2f)
        val maxY = max(0f, (drawnHeight - size.height) / 2f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun update(newScale: Float, newOffset: Offset) {
        val wasZoomed = scale > 1.01f
        scale = newScale
        offset = clamp(newOffset, newScale)
        val isZoomed = newScale > 1.01f
        if (wasZoomed != isZoomed) onZoomChanged(isZoomed)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            update(1f, Offset.Zero)
                        } else {
                            val target = DOUBLE_TAP_SCALE
                            val center = Offset(size.width / 2f, size.height / 2f)
                            update(target, (center - tap) * (target - 1f))
                        }
                    },
                )
            }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val focus = if (centroid == Offset.Unspecified) center else centroid
                            val effectiveZoom = newScale / scale
                            val newOffset = (offset + pan) * effectiveZoom + (focus - center) * (1f - effectiveZoom)
                            update(newScale, newOffset)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.01f) update(1f, Offset.Zero)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
private fun LoadingPage(thumbnail: Bitmap?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (thumbnail != null && !thumbnail.isRecycled) {
            Image(
                bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.6f,
            )
        }
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun FailedPage(reason: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        Text(reason, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun MediaInfoDialog(entry: VaultEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
        title = { Text(if (entry.isVideo()) "Video details" else "Photo details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoLine("Name", entry.displayName)
                InfoLine("Type", entry.mimeType)
                InfoLine("Size", Formatter.formatFileSize(context, entry.plaintextSize))
                if (entry.isVideo() && entry.durationMillis > 0L) InfoLine("Duration", formatDuration(entry.durationMillis))
                InfoLine(
                    "Added to vault",
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.importedAtEpochMillis)),
                )
                InfoLine("Protection", "Per-item AES-256 authenticated encryption; content is verified as it is read")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
