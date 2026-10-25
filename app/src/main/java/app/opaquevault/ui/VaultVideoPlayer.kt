package app.opaquevault.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOn
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opaquevault.VaultController
import app.opaquevault.media.EncryptedVaultDataSource
import app.opaquevault.vault.VaultEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

private val PLAYER_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val PanelColor = Color(0xE61B1B1F)
private val ScrimTop = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))

/**
 * Material 3 Expressive video player over the encrypted vault stream.
 *
 * Tap anywhere toggles the controls (they come back on every tap), double-tap the left or right
 * half to skip, swipe sideways to move to the next item even while playing (the old video pauses),
 * scrub with the slim seek bar. Hiding the controls also hides the system bars for a clean view.
 */
@OptIn(UnstableApi::class)
@Composable
fun VaultVideoPlayer(
    entry: VaultEntry,
    controller: VaultController,
    active: Boolean,
    allowInsecureQaScreenshots: Boolean,
    onClose: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings = controller.state.settings
    val skipSeconds = settings.skipSeconds
    val skipMillis = skipSeconds * 1_000L

    val thumbnail by produceState<Bitmap?>(initialValue = controller.cachedThumbnail(entry.id), entry.id) {
        if (value == null) value = controller.loadThumbnail(entry.id)
    }
    var controlsVisible by remember(entry.id) { mutableStateOf(true) }
    var playbackError by remember(entry.id) { mutableStateOf<String?>(null) }
    var isPlaying by remember(entry.id) { mutableStateOf(false) }
    var isBuffering by remember(entry.id) { mutableStateOf(false) }
    var hasEnded by remember(entry.id) { mutableStateOf(false) }
    var firstFrameShown by remember(entry.id) { mutableStateOf(false) }
    var positionMs by remember(entry.id) { mutableLongStateOf(0L) }
    var bufferedMs by remember(entry.id) { mutableLongStateOf(0L) }
    var durationMs by remember(entry.id) { mutableLongStateOf(entry.durationMillis) }
    var scrubbing by remember(entry.id) { mutableStateOf(false) }
    var scrubFraction by remember(entry.id) { mutableFloatStateOf(0f) }
    var speed by remember(entry.id) { mutableFloatStateOf(1f) }
    var muted by remember(entry.id) { mutableStateOf(settings.startVideosMuted) }
    var looping by remember(entry.id) { mutableStateOf(settings.loopVideos) }
    var fill by remember(entry.id) { mutableStateOf(false) }
    var speedMenu by remember(entry.id) { mutableStateOf(false) }
    var moreMenu by remember(entry.id) { mutableStateOf(false) }
    var touchTick by remember(entry.id) { mutableIntStateOf(0) }
    var skipBadge by remember(entry.id) { mutableStateOf<Pair<Boolean, Int>?>(null) }

    val player = remember(entry.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory { EncryptedVaultDataSource { controller.openVaultReader(entry.id) } },
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(skipMillis)
            .setSeekForwardIncrementMs(skipMillis)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri("opaque-vault://media/${entry.id}".toUri()))
                volume = if (settings.startVideosMuted) 0f else 1f
                repeatMode = if (settings.loopVideos) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                hasEnded = state == Player.STATE_ENDED
                if (hasEnded) controlsVisible = true
            }

            override fun onRenderedFirstFrame() {
                firstFrameShown = true
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = "This video couldn't be authenticated or decoded, so it was stopped."
                controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(looping) { player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    // Swiping away pauses this video; swiping back resumes only if autoplay is on.
    LaunchedEffect(active) {
        if (active) {
            controlsVisible = true
            if (settings.autoPlayVideos && !hasEnded) player.play()
        } else {
            player.pause()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            if (!scrubbing) positionMs = player.currentPosition.coerceAtLeast(0L)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            val reported = player.duration
            if (reported != C.TIME_UNSET && reported > 0L) durationMs = reported
            delay(if (isPlaying) 120L else 300L)
        }
    }
    // Auto-hide while playing, three seconds after the last touch.
    LaunchedEffect(controlsVisible, isPlaying, scrubbing, speedMenu, moreMenu, touchTick) {
        if (controlsVisible && isPlaying && !scrubbing && !speedMenu && !moreMenu) {
            delay(3_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(skipBadge) {
        if (skipBadge != null) {
            delay(700L)
            skipBadge = null
        }
    }
    // Immersive: system bars follow the controls on the visible page.
    DisposableEffect(activity, active, controlsVisible) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (active && insets != null) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controlsVisible) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun poke() {
        touchTick++
    }

    fun skip(forward: Boolean) {
        val limit = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val target = (player.currentPosition + if (forward) skipMillis else -skipMillis).coerceIn(0L, limit)
        player.seekTo(target)
        positionMs = target
        skipBadge = forward to skipSeconds
        poke()
    }

    fun togglePlay() {
        poke()
        when {
            hasEnded -> {
                player.seekTo(0L)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    // Callbacks read inside long-lived pointer handlers must always see the latest state.
    val toggleControls by rememberUpdatedState { controlsVisible = !controlsVisible; poke() }
    val skipBy by rememberUpdatedState<(Boolean) -> Unit> { forward -> skip(forward) }

    val accent = MaterialTheme.colorScheme.let { if (it.primary.luminance() < 0.45f) it.inversePrimary else it.primary }
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val poster = thumbnail
        if (!firstFrameShown && poster != null && !poster.isRecycled) {
            Image(
                bitmap = remember(poster) { poster.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    if (!allowInsecureQaScreenshots) (videoSurfaceView as? SurfaceView)?.setSecure(true)
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.keepScreenOn = settings.keepScreenOnDuringPlayback && active && isPlaying
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture layer. Taps don't consume drags, so the pager still swipes between items.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { toggleControls() },
                        onDoubleTap = { offset -> skipBy(offset.x > size.width / 2f) },
                    )
                },
        )

        if (isBuffering && playbackError == null) {
            CircularProgressIndicator(color = accent, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }

        // Skip feedback on the side that was tapped.
        skipBadge?.let { (forward, seconds) ->
            Surface(
                color = PanelColor,
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 32.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(skipIcon(forward, seconds), contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(
                        if (forward) "+${seconds}s" else "−${seconds}s",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- top bar
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScrimTop)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControl(Icons.AutoMirrored.Rounded.ArrowBack, "Back", size = 44.dp, onClick = onClose)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        entry.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (durationMs > 0L) formatDuration(durationMs) else "Video",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                RoundControl(Icons.Rounded.Info, "Details", size = 44.dp, onClick = { poke(); onInfo() })
                Box(Modifier.padding(start = 8.dp)) {
                    RoundControl(Icons.Rounded.MoreVert, "More", size = 44.dp, onClick = { moreMenu = true })
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (fill) "Fit to screen" else "Fill screen") },
                            leadingIcon = { Icon(if (fill) Icons.Rounded.AspectRatio else Icons.Rounded.Crop, null) },
                            onClick = { fill = !fill; moreMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Export") },
                            leadingIcon = { Icon(Icons.Rounded.Download, null) },
                            onClick = { moreMenu = false; player.pause(); onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete from vault") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { moreMenu = false; onDelete() },
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- centre transport
        AnimatedVisibility(
            visible = controlsVisible && playbackError == null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundControl(skipIcon(false, skipSeconds), "Back $skipSeconds seconds", size = 60.dp, iconSize = 30.dp) { skip(false) }
                // Expressive shape morph: a rounded square while paused, a circle while playing.
                val corner by animateDpAsState(
                    targetValue = if (isPlaying) 44.dp else 26.dp,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                    label = "playShape",
                )
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(accent)
                        .clickable(onClick = ::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            hasEnded -> Icons.Rounded.Replay
                            isPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = when {
                            hasEnded -> "Replay"
                            isPlaying -> "Pause"
                            else -> "Play"
                        },
                        tint = onAccent,
                        modifier = Modifier.size(46.dp),
                    )
                }
                RoundControl(skipIcon(true, skipSeconds), "Forward $skipSeconds seconds", size = 60.dp, iconSize = 30.dp) { skip(true) }
            }
        }

        // ---------------------------------------------------------------- bottom panel
        AnimatedVisibility(
            visible = controlsVisible && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val total = max(durationMs, 1L)
            val shown = if (scrubbing) (scrubFraction * total).toLong() else positionMs
            Surface(
                color = PanelColor,
                contentColor = Color.White,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(shown), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "−" + formatDuration((total - shown).coerceAtLeast(0L)),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    SlimSeekBar(
                        fraction = if (scrubbing) scrubFraction else (positionMs.toFloat() / total).coerceIn(0f, 1f),
                        bufferedFraction = (bufferedMs.toFloat() / total).coerceIn(0f, 1f),
                        activeColor = accent,
                        onScrubStart = { scrubbing = true; poke() },
                        onScrub = { scrubFraction = it; poke() },
                        onScrubEnd = {
                            val target = (scrubFraction * total).toLong()
                            player.seekTo(target)
                            positionMs = target
                            scrubbing = false
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            PillControl(
                                icon = Icons.Rounded.Speed,
                                label = speedLabel(speed),
                                selected = speed != 1f,
                                accent = accent,
                                onAccent = onAccent,
                                onClick = { speedMenu = true; poke() },
                            )
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                PLAYER_SPEEDS.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                speedLabel(option),
                                                fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = { speed = option; speedMenu = false },
                                    )
                                }
                            }
                        }
                        PillControl(
                            icon = if (looping) Icons.Rounded.RepeatOn else Icons.Rounded.Repeat,
                            label = "Loop",
                            selected = looping,
                            accent = accent,
                            onAccent = onAccent,
                            onClick = { looping = !looping; poke() },
                        )
                        Box(Modifier.weight(1f))
                        RoundControl(
                            if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            if (muted) "Unmute" else "Mute",
                            size = 40.dp,
                            iconSize = 22.dp,
                        ) { muted = !muted; poke() }
                        RoundControl(
                            if (fill) Icons.Rounded.AspectRatio else Icons.Rounded.Crop,
                            if (fill) "Fit to screen" else "Fill screen",
                            size = 40.dp,
                            iconSize = 22.dp,
                        ) { fill = !fill; poke() }
                    }
                }
            }
        }

        playbackError?.let { message ->
            Surface(
                color = PanelColor,
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

/** Thin, iOS/Material hybrid scrubber: 4 dp track, grows while dragging, tap anywhere to seek. */
@Composable
private fun SlimSeekBar(
    fraction: Float,
    bufferedFraction: Float,
    activeColor: Color,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (dragging) 8.dp else 4.dp, label = "track")
    val thumbSize by animateDpAsState(if (dragging) 20.dp else 12.dp, label = "thumb")
    val start by rememberUpdatedState(onScrubStart)
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    start()
                    scrub((offset.x / size.width).coerceIn(0f, 1f))
                    end()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        start()
                        scrub((offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrub((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { dragging = false; end() },
                    onDragCancel = { dragging = false; end() },
                )
            },
    ) {
        val track = trackHeight.toPx()
        val radius = CornerRadius(track / 2f)
        val top = (size.height - track) / 2f
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, track), radius)
        drawRoundRect(Color.White.copy(alpha = 0.40f), Offset(0f, top), Size(size.width * bufferedFraction, track), radius)
        drawRoundRect(activeColor, Offset(0f, top), Size(size.width * fraction, track), radius)
        drawCircle(Color.White, thumbSize.toPx() / 2f, Offset(size.width * fraction, size.height / 2f))
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp = 24.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(PanelColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun PillControl(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
) {
    val background = if (selected) accent else Color.White.copy(alpha = 0.12f)
    val content = if (selected) onAccent else Color.White
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Box(Modifier.width(6.dp))
        Text(label, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

private fun skipIcon(forward: Boolean, seconds: Int): ImageVector = when {
    forward && seconds <= 5 -> Icons.Rounded.Forward5
    forward && seconds >= 30 -> Icons.Rounded.Forward30
    forward -> Icons.Rounded.Forward10
    seconds <= 5 -> Icons.Rounded.Replay5
    seconds >= 30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay10
}
