package com.guopian.app.ui.swipe

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guopian.app.R
import com.guopian.app.core.util.VolumeBrightnessController
import com.guopian.app.domain.model.MarkStatus
import com.guopian.app.ui.cut.CutTargetSheet
import com.guopian.app.ui.util.OpenDocumentTreeContract
import com.guopian.app.ui.util.launchDocumentTreePreferAndroid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeBrowseScreen(
    onBack: () -> Unit,
    viewModel: SwipeBrowseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val presets by viewModel.folderPresets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val total = uiState.total.coerceAtLeast(1)
    val activity = LocalContext.current as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 进入播放页允许传感器旋转；勿绑 isLandscape，否则切方向时 onDispose 会把锁定方向冲掉
    DisposableEffect(Unit) {
        val previousOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (previousOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT &&
                previousOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ) {
                activity?.requestedOrientation = previousOrientation
            }
        }
    }

    // 旋转不重建 Activity（configChanges），仅随方向重设系统栏；不要改 requestedOrientation
    DisposableEffect(isLandscape) {
        val window = activity?.window
        val previousNavColor = window?.navigationBarColor
        val previousStatusColor = window?.statusBarColor
        val previousContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isNavigationBarContrastEnforced
        } else {
            null
        }
        val previousStatusContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isStatusBarContrastEnforced
        } else {
            null
        }
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousLightNav = controller?.isAppearanceLightNavigationBars
        val previousLightStatus = controller?.isAppearanceLightStatusBars

        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
        }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.navigationBars())
        if (isLandscape) {
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousNavColor != null) window?.navigationBarColor = previousNavColor
            if (previousStatusColor != null) window?.statusBarColor = previousStatusColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (previousContrast != null) {
                    window?.isNavigationBarContrastEnforced = previousContrast
                }
                if (previousStatusContrast != null) {
                    window?.isStatusBarContrastEnforced = previousStatusContrast
                }
            }
            if (previousLightNav != null) {
                controller.isAppearanceLightNavigationBars = previousLightNav
            }
            if (previousLightStatus != null) {
                controller.isAppearanceLightStatusBars = previousLightStatus
            }
        }
    }

    LaunchedEffect(uiState.uiVisible, uiState.isPlaying, uiState.controlsEpoch) {
        if (uiState.uiVisible && uiState.isPlaying) {
            delay(3200)
            viewModel.hideUi()
        }
    }

    val pickCutTarget = rememberLauncherForActivityResult(
        contract = OpenDocumentTreeContract(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.enqueueCut(uri)
    }
    val gestureScope = rememberCoroutineScope()
    var edgeHud by remember { mutableStateOf<EdgeGestureHud?>(null) }
    var edgeHudValue by remember { mutableFloatStateOf(0f) }
    var gestureAreaHeightPx by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(edgeHud) {
        // 拖动中的 HUD 由 onDragEnd 自行消失，这里只兜底清理残留
        if (edgeHud != null) {
            delay(1600)
            edgeHud = null
        }
    }

    LaunchedEffect(uiState.cutMessage) {
        val msg = uiState.cutMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearCutMessage()
    }

    if (uiState.showCutSheet) {
        CutTargetSheet(
            count = 1,
            presets = presets,
            policy = uiState.conflictPolicy,
            onPolicyChange = viewModel::setConflictPolicy,
            onPickFolder = { pickCutTarget.launchDocumentTreePreferAndroid() },
            onPresetClick = viewModel::enqueueCutPreset,
            onDismiss = viewModel::dismissCutSheet,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (!uiState.ready) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = uiState.error ?: stringResource(R.string.swipe_loading),
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onBack) {
                    Text(text = stringResource(R.string.action_back))
                }
            }
        } else {
            key(uiState.playlistEpoch) {
                val epochPager = rememberPagerState(
                    initialPage = uiState.currentIndex.coerceIn(0, (total - 1).coerceAtLeast(0)),
                    pageCount = { uiState.total.coerceAtLeast(1) },
                )
                LaunchedEffect(epochPager) {
                    snapshotFlow { epochPager.settledPage }
                        .distinctUntilChanged()
                        .collect { page -> viewModel.onPageSettled(page) }
                }
                LaunchedEffect(uiState.pendingScrollIndex, uiState.playlistEpoch) {
                    val target = uiState.pendingScrollIndex ?: return@LaunchedEffect
                    epochPager.scrollToPage(target.coerceIn(0, (uiState.total - 1).coerceAtLeast(0)))
                    viewModel.consumePendingScroll()
                }
                VerticalPager(
                    state = epochPager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { page -> "${uiState.playlistEpoch}-$page" },
                ) { page ->
                    val aspects by viewModel.aspectByVideoId.collectAsStateWithLifecycle()
                    val videoId = viewModel.idAt(page)
                    val size = videoId?.let { aspects[it] }
                    VideoPage(
                        page = page,
                        isCurrent = page == epochPager.settledPage,
                        videoWidth = size?.first ?: 0,
                        videoHeight = size?.second ?: 0,
                        viewModel = viewModel,
                        onTap = { viewModel.toggleUi() },
                        onDoubleTap = viewModel::doubleTapKeep,
                        onLongPress = { viewModel.speedBoost(true) },
                        onLongPressRelease = { viewModel.speedBoost(false) },
                    )
                }
            }

            if (uiState.buffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }

            // 仅用户主动暂停时显示中心播放钮，切页自动播不闪现
            if (uiState.pausedByUser && !uiState.isPlaying && !uiState.buffering) {
                ModernCenterPlay(
                    modifier = Modifier.align(Alignment.Center),
                    onPlay = {
                        viewModel.togglePlay()
                        viewModel.showUi()
                    },
                )
            }

            // 双击/点收藏：中心心形特效（不依赖控件栏是否显示）
            KeepBurstOverlay(
                epoch = uiState.keepBurstEpoch,
                kept = uiState.keepBurstKept,
                modifier = Modifier.align(Alignment.Center),
            )

            // 左音量 / 右亮度收窄到两侧，中间大半留给上下过片
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { gestureAreaHeightPx = it.height.toFloat().coerceAtLeast(1f) },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(0.16f)
                        .pointerInput(gestureAreaHeightPx) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    edgeHud = EdgeGestureHud.Volume
                                    edgeHudValue = VolumeBrightnessController.getVolumeFraction(activity ?: return@detectVerticalDragGestures)
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    val ctx = activity ?: return@detectVerticalDragGestures
                                    edgeHud = EdgeGestureHud.Volume
                                    edgeHudValue = VolumeBrightnessController.adjustVolumeByDrag(
                                        ctx,
                                        dragAmount,
                                        gestureAreaHeightPx,
                                    )
                                },
                                onDragEnd = {
                                    gestureScope.launch {
                                        delay(600)
                                        if (edgeHud == EdgeGestureHud.Volume) edgeHud = null
                                    }
                                },
                                onDragCancel = { edgeHud = null },
                            )
                        },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.16f)
                        .pointerInput(gestureAreaHeightPx, activity) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    val act = activity ?: return@detectVerticalDragGestures
                                    edgeHud = EdgeGestureHud.Brightness
                                    edgeHudValue = VolumeBrightnessController.getBrightnessFraction(act)
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    val act = activity ?: return@detectVerticalDragGestures
                                    edgeHud = EdgeGestureHud.Brightness
                                    edgeHudValue = VolumeBrightnessController.adjustBrightnessByDrag(
                                        act,
                                        dragAmount,
                                        gestureAreaHeightPx,
                                    )
                                },
                                onDragEnd = {
                                    gestureScope.launch {
                                        delay(600)
                                        if (edgeHud == EdgeGestureHud.Brightness) edgeHud = null
                                    }
                                },
                                onDragCancel = { edgeHud = null },
                            )
                        },
                )
            }

            edgeHud?.let { kind ->
                EdgeGestureIndicator(
                    kind = kind,
                    value = edgeHudValue,
                    modifier = Modifier
                        .align(
                            when (kind) {
                                EdgeGestureHud.Volume -> Alignment.CenterStart
                                EdgeGestureHud.Brightness -> Alignment.CenterEnd
                            },
                        )
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(
                            start = if (kind == EdgeGestureHud.Volume) 28.dp else 0.dp,
                            end = if (kind == EdgeGestureHud.Brightness) 28.dp else 0.dp,
                        ),
                )
            }

            if (uiState.uiVisible) {
                // 顶栏：safeDrawing 避开刘海/状态栏/横屏裁切
                ModernTopBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    indexLabel = if (uiState.pageCount > 1) {
                        stringResource(
                            R.string.swipe_page_indicator,
                            uiState.pageIndex + 1,
                            uiState.pageCount,
                            uiState.total,
                            uiState.totalInLibrary,
                        )
                    } else {
                        "${uiState.currentIndex + 1} / ${uiState.total}"
                    },
                    isLandscape = isLandscape,
                    onBack = onBack,
                    onToggleOrientation = {
                        activity?.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        viewModel.showUi()
                    },
                )

                ModernSideRail(
                    modifier = Modifier
                        .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(
                            end = 10.dp,
                            bottom = if (isLandscape) 64.dp else 120.dp,
                        ),
                    markStatus = uiState.markStatus,
                    keepBurstEpoch = uiState.keepBurstEpoch,
                    onToggleKeep = viewModel::toggleKeep,
                    onTogglePending = viewModel::togglePending,
                )

                ModernBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(
                            if (isLandscape) Modifier.padding(bottom = 4.dp)
                            else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                        ),
                    fileName = uiState.video?.fileName.orEmpty(),
                    relativePath = uiState.video?.relativePath.orEmpty(),
                    sizeBytes = uiState.video?.sizeBytes ?: 0L,
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    isPlaying = uiState.isPlaying,
                    playbackSpeed = uiState.playbackSpeed,
                    showMeta = !isLandscape,
                    compact = isLandscape,
                    endReserve = if (isLandscape) 8.dp else 0.dp,
                    onSeekFraction = { fraction ->
                        viewModel.beginScrub()
                        viewModel.scrubToFraction(fraction)
                    },
                    onSeekFinished = viewModel::endScrub,
                    onSeekStart = viewModel::beginScrub,
                    onTogglePlay = {
                        viewModel.togglePlay()
                        viewModel.showUi()
                    },
                    onPrevious = viewModel::goPrevious,
                    onNext = viewModel::goNext,
                    canGoPrevious = uiState.currentIndex > 0 || uiState.pageIndex > 0,
                    canGoNext = uiState.currentIndex + 1 < uiState.total ||
                        uiState.pageIndex + 1 < uiState.pageCount,
                    onCycleSpeed = viewModel::cycleSpeed,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 16.dp),
            )

            val err = uiState.error
            if (err != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = err, color = Color(0xFFFFB4A8))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::trySoftDecode) {
                            Text(text = stringResource(R.string.action_try_soft), color = Color.White)
                        }
                        if (uiState.showExternalAction) {
                            TextButton(onClick = viewModel::openExternal) {
                                Text(text = stringResource(R.string.action_open_external), color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPage(
    page: Int,
    isCurrent: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    viewModel: SwipeBrowseViewModel,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onLongPressRelease: () -> Unit,
) {
    // 未知比例时先黑屏并触发预取，绝不先用错误比例铺满后再纠正
    LaunchedEffect(page, videoWidth, videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            viewModel.prefetchAspectForPage(page)
        }
    }
    val aspectReady = videoWidth > 0 && videoHeight > 0
    val aspect = if (aspectReady) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(page) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() },
                    onPress = {
                        tryAwaitRelease()
                        onLongPressRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (aspectReady) {
            AndroidView(
                factory = { context ->
                    androidx.media3.ui.AspectRatioFrameLayout(context).apply {
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setAspectRatio(aspect)
                        val surface = SurfaceView(context)
                        addView(
                            surface,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        viewModel.registerSurface(page, surface)
                        viewModel.engineForPage(page)?.attachSurface(surface)
                    }
                },
                update = { frame ->
                    frame.setAspectRatio(aspect)
                    val surface = frame.getChildAt(0) as? SurfaceView ?: return@AndroidView
                    viewModel.registerSurface(page, surface)
                    if (isCurrent) {
                        viewModel.engineForPage(page)?.attachSurface(surface)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        DisposableEffect(page) {
            onDispose { }
        }
    }
}

private enum class EdgeGestureHud { Volume, Brightness }

@Composable
private fun EdgeGestureIndicator(
    kind: EdgeGestureHud,
    value: Float,
    modifier: Modifier = Modifier,
) {
    val label = when (kind) {
        EdgeGestureHud.Volume -> stringResource(R.string.gesture_volume)
        EdgeGestureHud.Brightness -> stringResource(R.string.gesture_brightness)
    }
    val pct = (value.coerceIn(0f, 1f) * 100f).toInt()
    val filled = value.coerceIn(0f, 1f)
    val volumeSide = kind == EdgeGestureHud.Volume
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.52f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!volumeSide) {
            Column(horizontalAlignment = Alignment.End) {
                Text(text = label, color = Color.White.copy(alpha = 0.75f))
                Text(text = "$pct%", color = Color.White)
            }
        }
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(filled)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.92f)),
            )
        }
        if (volumeSide) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(text = label, color = Color.White.copy(alpha = 0.75f))
                Text(text = "$pct%", color = Color.White)
            }
        }
    }
}
