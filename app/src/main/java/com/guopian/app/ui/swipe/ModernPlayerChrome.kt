package com.guopian.app.ui.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guopian.app.R
import com.guopian.app.core.util.FileSizeFormatter
import com.guopian.app.core.util.PathAbbreviator
import com.guopian.app.domain.model.MarkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

private val PlayerAccent = Color(0xFF3B82F6)
private val PlayerInk = Color.White
private val PlayerMute = Color.White.copy(alpha = 0.62f)
private val ControlFill = Color.Black.copy(alpha = 0.42f)
private val KeepHeart = Color(0xFFFF2D55)

@Composable
fun ModernTopBar(
    modifier: Modifier = Modifier,
    indexLabel: String,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onToggleOrientation: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularControl(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
        )
        CircularControl(
            icon = Icons.Filled.ScreenRotation,
            contentDescription = stringResource(
                if (isLandscape) R.string.action_portrait else R.string.action_landscape,
            ),
            onClick = onToggleOrientation,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = indexLabel,
            color = PlayerMute,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ControlFill)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun ModernBottomBar(
    modifier: Modifier = Modifier,
    fileName: String,
    relativePath: String,
    sizeBytes: Long = 0L,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    showMeta: Boolean,
    compact: Boolean = false,
    endReserve: Dp,
    onSeekFraction: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    onSeekStart: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onCycleSpeed: () -> Unit,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0) {
        (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    // 拖进度时锁定按钮态，不跟引擎短暂 pause 闪烁
    var playIconLocked by remember { mutableStateOf<Boolean?>(null) }
    val shownPlaying = playIconLocked ?: isPlaying
    val shown = if (scrubbing) scrubValue else progress
    val speedLabel = formatSpeed(playbackSpeed)
    val sizeLabel = remember(sizeBytes) {
        if (sizeBytes > 0L) FileSizeFormatter.format(sizeBytes) else ""
    }
    val onScrubMove: (Float) -> Unit = { fraction ->
        if (!scrubbing) {
            playIconLocked = isPlaying
        }
        scrubbing = true
        scrubValue = fraction
        onSeekFraction(fraction)
    }
    val onScrubDone: () -> Unit = {
        val finalValue = scrubValue
        scrubbing = false
        playIconLocked = null
        onSeekFinished(finalValue)
    }

    if (compact) {
        // 横屏：单行紧凑控件，显著降低底栏占高
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(start = 12.dp, end = 12.dp + endReserve, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpeedChip(
                label = speedLabel,
                active = kotlin.math.abs(playbackSpeed - 1f) > 0.01f,
                onClick = onCycleSpeed,
                compact = true,
            )
            if (sizeLabel.isNotBlank()) {
                Text(
                    text = sizeLabel,
                    color = PlayerMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Text(
                text = formatPlayerTime(
                    if (scrubbing) (scrubValue * duration).toLong() else positionMs,
                ),
                color = PlayerMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            ModernScrubber(
                progress = shown,
                onScrubStart = onSeekStart,
                onScrub = onScrubMove,
                onScrubEnd = onScrubDone,
                modifier = Modifier.weight(1f),
                trackHeight = 3.dp,
            )
            Text(
                text = formatPlayerTime(duration),
                color = PlayerMute,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            CircularControl(
                icon = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous),
                onClick = onPrevious,
                size = 32.dp,
                enabled = canGoPrevious,
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PlayerAccent)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (shownPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (shownPlaying) R.string.action_pause else R.string.action_play,
                    ),
                    tint = PlayerInk,
                    modifier = Modifier.size(20.dp),
                )
            }
            CircularControl(
                icon = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.action_next),
                onClick = onNext,
                size = 32.dp,
                enabled = canGoNext,
            )
        }
        return
    }

    val topPad = 18.dp
    val bottomPad = 10.dp
    val playSize = 52.dp
    val skipSize = 40.dp
    val playIcon = 28.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f),
                        Color.Black.copy(alpha = 0.82f),
                    ),
                ),
            )
            .padding(start = 14.dp, end = 14.dp + endReserve, top = topPad, bottom = bottomPad),
    ) {
        if (showMeta && (fileName.isNotBlank() || sizeLabel.isNotBlank())) {
            if (fileName.isNotBlank()) {
                Text(
                    text = fileName,
                    color = PlayerInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val metaLine = buildList {
                if (relativePath.isNotBlank()) add(PathAbbreviator.abbreviate(relativePath))
                if (sizeLabel.isNotBlank()) add(sizeLabel)
            }.joinToString(" · ")
            if (metaLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = metaLine,
                    color = PlayerMute,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        ModernScrubber(
            progress = shown,
            onScrubStart = onSeekStart,
            onScrub = onScrubMove,
            onScrubEnd = onScrubDone,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlayerTime(
                    if (scrubbing) (scrubValue * duration).toLong() else positionMs,
                ),
                color = PlayerMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatPlayerTime(duration),
                color = PlayerMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SpeedChip(
                label = speedLabel,
                active = kotlin.math.abs(playbackSpeed - 1f) > 0.01f,
                onClick = onCycleSpeed,
                compact = false,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularControl(
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.action_previous),
                    onClick = onPrevious,
                    size = skipSize,
                    enabled = canGoPrevious,
                )
                Box(
                    modifier = Modifier
                        .size(playSize)
                        .clip(CircleShape)
                        .background(PlayerAccent)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (shownPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (shownPlaying) R.string.action_pause else R.string.action_play,
                        ),
                        tint = PlayerInk,
                        modifier = Modifier.size(playIcon),
                    )
                }
                CircularControl(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.action_next),
                    onClick = onNext,
                    size = skipSize,
                    enabled = canGoNext,
                )
            }

            Box(modifier = Modifier.size(width = 56.dp, height = 32.dp))
        }
    }
}

@Composable
private fun SpeedChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    Text(
        text = label,
        color = if (active) PlayerAccent else PlayerInk,
        fontSize = if (compact) 12.sp else 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 8.dp else 10.dp))
            .background(if (active) PlayerAccent.copy(alpha = 0.18f) else ControlFill)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 6.dp else 10.dp,
            ),
    )
}

@Composable
fun ModernCenterPlay(
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlay,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.action_play),
            tint = PlayerInk,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * 双击/点收藏时的中心心形特效：放大弹入 → 停顿 → 淡出。
 */
@Composable
fun KeepBurstOverlay(
    epoch: Int,
    kept: Boolean,
    modifier: Modifier = Modifier,
) {
    if (epoch <= 0) return
    key(epoch) {
        val scale = remember { Animatable(0.35f) }
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(epoch) {
            alpha.snapTo(0f)
            scale.snapTo(0.35f)
            launch {
                alpha.animateTo(1f, tween(90))
            }
            scale.animateTo(
                1.18f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            scale.animateTo(1f, tween(120, easing = FastOutSlowInEasing))
            delay(520)
            alpha.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        }
        Column(
            modifier = modifier
                .scale(scale.value)
                .alpha(alpha.value),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (kept) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = if (kept) KeepHeart else Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (kept) R.string.keep_burst_on else R.string.keep_burst_off,
                ),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        blurRadius = 8f,
                    ),
                ),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun ModernSideRail(
    modifier: Modifier = Modifier,
    markStatus: MarkStatus,
    keepBurstEpoch: Int = 0,
    onToggleKeep: () -> Unit,
    onTogglePending: () -> Unit,
) {
    val kept = markStatus == MarkStatus.KEEP
    val pending = markStatus == MarkStatus.PENDING
    val heartScale = remember { Animatable(1f) }
    LaunchedEffect(keepBurstEpoch) {
        if (keepBurstEpoch <= 0) return@LaunchedEffect
        heartScale.snapTo(1f)
        heartScale.animateTo(
            1.28f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
        heartScale.animateTo(1f, tween(140))
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SideRailItem(
            icon = if (kept) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            label = stringResource(R.string.action_like),
            tint = if (kept) KeepHeart else PlayerInk,
            onClick = onToggleKeep,
            iconScale = heartScale.value,
        )
        SideRailItem(
            icon = Icons.Outlined.Flag,
            label = stringResource(R.string.mark_pending),
            tint = if (pending) PlayerAccent else PlayerInk,
            onClick = onTogglePending,
        )
    }
}

@Composable
private fun SideRailItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    iconScale: Float = 1f,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.scale(iconScale),
    ) {
        CircularControl(icon = icon, contentDescription = label, tint = tint, onClick = onClick, size = 46.dp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = PlayerMute,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun CircularControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = PlayerInk,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ControlFill.copy(alpha = if (enabled) ControlFill.alpha else 0.28f))
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
private fun ModernScrubber(
    progress: Float,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 3.dp,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val thumbRadius = 7.dp
    val hitHeight = 36.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(hitHeight)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(widthPx) {
                val w = widthPx.coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = (down.position.x / w).coerceIn(0f, 1f)
                    onScrubStart()
                    onScrub(start)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val fraction = (change.position.x / w).coerceIn(0f, 1f)
                            onScrub(fraction)
                            if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) {
                                change.consume()
                            }
                        } else {
                            onScrubEnd()
                            change.consume()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = PlayerAccent,
                size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
                cornerRadius = radius,
            )
        }
        val thumbOffset = with(density) {
            ((widthPx * progress.coerceIn(0f, 1f)) - thumbRadius.toPx()).coerceAtLeast(0f).toDp()
        }
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbRadius * 2)
                .clip(CircleShape)
                .background(PlayerInk),
        )
    }
}

private fun formatPlayerTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(safe)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

private fun formatSpeed(speed: Float): String {
    return when {
        kotlin.math.abs(speed - 0.75f) < 0.01f -> "0.75x"
        kotlin.math.abs(speed - 1f) < 0.01f -> "1.0x"
        kotlin.math.abs(speed - 1.25f) < 0.01f -> "1.25x"
        kotlin.math.abs(speed - 1.5f) < 0.01f -> "1.5x"
        kotlin.math.abs(speed - 2f) < 0.01f -> "2.0x"
        else -> String.format(Locale.US, "%.2fx", speed)
    }
}
