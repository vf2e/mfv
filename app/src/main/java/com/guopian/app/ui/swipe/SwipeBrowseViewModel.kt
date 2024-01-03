package com.guopian.app.ui.swipe

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guopian.app.core.util.VideoExtensions
import com.guopian.app.data.prefs.PlayerPrefer
import com.guopian.app.data.prefs.SettingsRepository
import com.guopian.app.data.db.FolderPresetDao
import com.guopian.app.data.db.FolderPresetEntity
import com.guopian.app.data.db.PlaybackProgressDao
import com.guopian.app.data.db.PlaybackProgressEntity
import com.guopian.app.data.db.VideoEntity
import com.guopian.app.data.repo.RootBrowseStateStore
import com.guopian.app.data.repo.RootFolderRepository
import com.guopian.app.data.repo.VideoRepository
import com.guopian.app.domain.model.ConflictPolicy
import com.guopian.app.domain.model.MarkStatus
import com.guopian.app.domain.usecase.MarkVideoUseCase
import com.guopian.app.media.engine.EngineId
import com.guopian.app.media.engine.MediaRef
import com.guopian.app.media.engine.PlayerEngine
import com.guopian.app.media.engine.PlayerEvent
import com.guopian.app.media.pool.PlayerPool
import com.guopian.app.media.router.ExternalPlayerLauncher
import com.guopian.app.media.router.FailoverTracker
import com.guopian.app.media.thumb.VideoAspectProbe
import com.guopian.app.player.playlist.PlaylistSession
import com.guopian.app.work.MoveScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class SwipeUiState(
    val ready: Boolean = false,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val totalInLibrary: Int = 0,
    /** 换页时递增，强制重建 Pager。 */
    val playlistEpoch: Int = 0,
    val video: VideoEntity? = null,
    val isPlaying: Boolean = true,
    val uiVisible: Boolean = true,
    val controlsEpoch: Int = 0,
    val markStatus: MarkStatus = MarkStatus.TO_DELETE,
    val error: String? = null,
    val showExternalAction: Boolean = false,
    val engineLabel: String = "EXO",
    val showCutSheet: Boolean = false,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
    val cutMessage: String? = null,
    /** One-shot: UI scrolls pager then clears. */
    val pendingScrollIndex: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val buffering: Boolean = false,
    val playbackSpeed: Float = 1f,
    /** 用户主动暂停时才显示中心播放钮，切页过程不闪现。 */
    val pausedByUser: Boolean = false,
    /** 当前片源显示宽高（已考虑旋转），用于 Surface 等比适配。 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** 收藏/取消特效：epoch 递增触发一次动画；kept=是否变为收藏。 */
    val keepBurstEpoch: Int = 0,
    val keepBurstKept: Boolean = false,
)

@HiltViewModel
class SwipeBrowseViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val playlistSession: PlaylistSession,
    private val videoRepository: VideoRepository,
    private val markVideoUseCase: MarkVideoUseCase,
    private val playerPool: PlayerPool,
    private val failoverTracker: FailoverTracker,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
    private val moveScheduler: MoveScheduler,
    private val folderPresetDao: FolderPresetDao,
    private val settingsRepository: SettingsRepository,
    private val playbackProgressDao: PlaybackProgressDao,
    private val rootFolderRepository: RootFolderRepository,
    private val browseStateStore: RootBrowseStateStore,
) : ViewModel() {

    private val startIndexArg: Int = savedStateHandle["startIndex"] ?: 0

    private val _uiState = MutableStateFlow(SwipeUiState())
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    /** videoId → 显示宽高，相邻页预取后切页可立刻定框。 */
    private val _aspectByVideoId = MutableStateFlow<Map<Long, Pair<Int, Int>>>(emptyMap())
    val aspectByVideoId: StateFlow<Map<Long, Pair<Int, Int>>> = _aspectByVideoId.asStateFlow()

    val folderPresets: StateFlow<List<FolderPresetEntity>> = folderPresetDao.observeRecent()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private var eventJob: Job? = null
    private var positionJob: Job? = null
    private var settleJob: Job? = null
    private var preloadRadius: Int = 1
    private val surfaces = mutableMapOf<Int, SurfaceView>()
    private val aspectJobs = mutableMapOf<Long, Job>()
    @Volatile private var scrubbing: Boolean = false
    /** 拖进度开始时的播放态，拖动全程与松手后都保持，不被 seek 中间态改掉。 */
    private var scrubWasPlaying: Boolean? = null
    private var lastScrubSeekAtMs: Long = 0L
    private var seekGuardUntilElapsedMs: Long = 0L
    private var lastProgressPersistAtMs: Long = 0L

    init {
        val snap = playlistSession.current()
        if (snap == null || snap.ids.isEmpty()) {
            _uiState.update { it.copy(error = "播放列表为空") }
        } else {
            val index = startIndexArg.coerceIn(0, snap.ids.lastIndex)
            _uiState.update {
                it.copy(
                    ready = true,
                    currentIndex = index,
                    total = snap.ids.size,
                    pageIndex = snap.pageIndex,
                    pageCount = snap.pageCount,
                    totalInLibrary = snap.totalCount,
                    playlistEpoch = 1,
                )
            }
            onPageSettled(index)
        }
    }

    fun idAt(page: Int): Long? = playlistSession.idAt(page)

    fun registerSurface(index: Int, surface: SurfaceView) {
        surfaces[index] = surface
        // 不在这里新建播放器，只把 surface 绑到已有引擎，避免快滑创建过多解码器
        playerPool.getOrNull(index)?.attachSurface(surface)
    }

    fun prefetchAspectForPage(page: Int) {
        val id = playlistSession.idAt(page) ?: return
        if (_aspectByVideoId.value.containsKey(id)) return
        if (aspectJobs[id]?.isActive == true) return
        aspectJobs[id] = viewModelScope.launch {
            val entity = videoRepository.getById(id) ?: return@launch
            ensureAspect(entity)
        }
    }

    fun onPageSettled(index: Int) {
        val snap = playlistSession.current() ?: return
        if (index !in snap.ids.indices) return
        val leavingVideoId = _uiState.value.video?.id
        val leavingPos = _uiState.value.positionMs
        val leavingDur = _uiState.value.durationMs
        val keep = ((index - preloadRadius)..(index + preloadRadius))
            .filter { it in snap.ids.indices }
            .toSet()
        playerPool.retainOnly(keep)
        // 清掉已不在 keep 范围的 surface 引用，防止泄漏
        surfaces.keys.filter { it !in keep }.toList().forEach { surfaces.remove(it) }

        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            if (leavingVideoId != null && leavingVideoId != snap.ids[index]) {
                persistProgress(leavingVideoId, leavingPos, leavingDur)
            }
            keep.map { i ->
                async {
                    val entity = videoRepository.getById(snap.ids[i]) ?: return@async
                    ensureAspect(entity)
                }
            }.awaitAll()

            val resumeAt = resumePositionFor(snap.ids[index])
            val playerPrefer = settingsRepository.playerPrefer.first()
            // 先绑定当前页（VLC 全局仅一份），再预载邻页 Exo
            val ordered = keep.sortedBy { if (it == index) 0 else 1 }
            ordered.forEach { i ->
                val id = snap.ids[i]
                val entity = videoRepository.getById(id) ?: return@forEach
                val media = entity.toMediaRef()
                val startMs = if (i == index) resumeAt else 0L
                val soft = shouldUseVlc(entity, id, playerPrefer)
                val engine = when {
                    soft && i == index ->
                        playerPool.failoverToVlc(i, media, surfaces[i], startMs)
                    soft && i != index -> {
                        // 邻页兼容格式不预载，避免冲掉当前 VLC / 触发 Exo 闪退
                        return@forEach
                    }
                    else -> runCatching {
                        playerPool.acquireExo(i, pivotIndex = index).also {
                            surfaces[i]?.let { s -> it.attachSurface(s) }
                            it.load(media, startMs)
                        }
                    }.getOrElse {
                        if (i == index) {
                            failoverTracker.markExoFailed(id)
                            playerPool.failoverToVlc(i, media, surfaces[i], startMs)
                        } else {
                            return@forEach
                        }
                    }
                }
                if (i == index) {
                    engine.setSpeed(_uiState.value.playbackSpeed)
                    if (!_uiState.value.pausedByUser) {
                        engine.play()
                    } else {
                        engine.pause()
                    }
                } else {
                    engine.pause()
                }
            }
            val currentId = snap.ids[index]
            val video = videoRepository.getById(currentId)
            val engine = playerPool.get(index)
            val (dispW, dispH) = aspectOf(video?.id) ?: (0 to 0)
            val engineDur = engine?.getDurationMs()?.takeIf { it > 0 } ?: 0L
            val entityDur = video?.durationMs?.takeIf { it > 0 } ?: 0L
            _uiState.update {
                it.copy(
                    currentIndex = index,
                    total = snap.ids.size,
                    pageIndex = snap.pageIndex,
                    pageCount = snap.pageCount,
                    totalInLibrary = snap.totalCount,
                    video = video,
                    markStatus = video?.let { v -> markVideoUseCase.statusOf(v) } ?: MarkStatus.TO_DELETE,
                    isPlaying = true,
                    pausedByUser = false,
                    error = null,
                    showExternalAction = false,
                    engineLabel = engine?.engineId?.name ?: "EXO",
                    positionMs = if (resumeAt > 0L) resumeAt else (engine?.getPositionMs() ?: 0L),
                    durationMs = engineDur.takeIf { it > 0 } ?: entityDur,
                    buffering = false,
                    videoWidth = dispW,
                    videoHeight = dispH,
                )
            }
            video?.let { persistBrowseCursor(it) }
            bindEvents(index)
            startPositionTicker(index)
        }
    }

    private fun shouldUseVlc(
        entity: VideoEntity,
        videoId: Long,
        prefer: PlayerPrefer,
    ): Boolean {
        if (prefer == PlayerPrefer.VLC_FIRST) return true
        // AVI 等格式 Exo 可能原生闪退，必须直接走 VLC
        if (VideoExtensions.preferVlc(entity.ext)) return true
        if (failoverTracker.shouldSkipExo(videoId)) return true
        return false
    }

    private fun persistBrowseCursor(video: VideoEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val root = rootFolderRepository.getCurrent() ?: return@launch
            val treeUri = Uri.parse(root.treeUri)
            browseStateStore.updateLast(treeUri, video.relativePath)
        }
    }

    private suspend fun ensureAspect(video: VideoEntity): Pair<Int, Int>? {
        aspectOf(video.id)?.let { return it }
        val fromEntity = displaySizeOf(video)
        if (fromEntity.first > 0 && fromEntity.second > 0) {
            putAspect(video.id, fromEntity)
            return fromEntity
        }
        val probed = withContext(Dispatchers.IO) {
            VideoAspectProbe.probe(appContext, Uri.parse(video.docUri))
        }
        if (probed != null) {
            if (probed.width > 0 && probed.height > 0) {
                val display = VideoAspectProbe.toDisplayPair(probed.width, probed.height, probed.rotation)
                if (display.first > 0 && display.second > 0) {
                    putAspect(video.id, display)
                    videoRepository.saveDisplaySizeIfEmpty(
                        video.id,
                        probed.width,
                        probed.height,
                        probed.rotation,
                    )
                }
                if (probed.durationMs > 0L) {
                    videoRepository.saveDurationIfEmpty(video.id, probed.durationMs)
                }
                return displaySizeOf(
                    video.copy(
                        width = probed.width,
                        height = probed.height,
                        rotation = probed.rotation,
                    ),
                ).takeIf { it.first > 0 && it.second > 0 }
                    ?: aspectOf(video.id)
            }
            if (probed.durationMs > 0L) {
                videoRepository.saveDurationIfEmpty(video.id, probed.durationMs)
            }
        }
        return null
    }

    private fun putAspect(videoId: Long, size: Pair<Int, Int>) {
        if (size.first <= 0 || size.second <= 0) return
        _aspectByVideoId.update { cur ->
            if (cur[videoId] == size) return@update cur
            val next = cur + (videoId to size)
            // 防止超长播放列表时 Map 无限膨胀
            if (next.size <= 400) next
            else next.entries.toList().takeLast(300).associate { it.key to it.value }
        }
    }

    private fun aspectOf(videoId: Long?): Pair<Int, Int>? {
        if (videoId == null) return null
        return _aspectByVideoId.value[videoId]
    }

    private fun bindEvents(index: Int) {
        eventJob?.cancel()
        val engine = playerPool.get(index) ?: return
        eventJob = viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is PlayerEvent.Playing -> {
                        // 拖进度时 Exo 会短暂停一下，忽略中间态，避免播放按钮乱跳
                        if (!scrubbing) {
                            _uiState.update { it.copy(isPlaying = event.playing, buffering = false) }
                        }
                    }
                    is PlayerEvent.Buffering -> {
                        if (!scrubbing) {
                            _uiState.update { it.copy(buffering = true) }
                        }
                    }
                    is PlayerEvent.Ready -> {
                        val dur = engine.getDurationMs()
                        _uiState.update {
                            it.copy(
                                buffering = false,
                                durationMs = if (dur > 0) dur else it.durationMs,
                            )
                        }
                        if (dur > 0L) {
                            val id = _uiState.value.video?.id
                            if (id != null) {
                                viewModelScope.launch {
                                    videoRepository.saveDurationIfEmpty(id, dur)
                                }
                            }
                        }
                    }
                    is PlayerEvent.Position -> {
                        if (!scrubbing) {
                            _uiState.update {
                                it.copy(positionMs = event.positionMs, durationMs = event.durationMs)
                            }
                        }
                    }
                    is PlayerEvent.VideoSize -> {
                        val (w, h) = displaySize(event.width, event.height, event.rotationDegrees)
                        if (w > 0 && h > 0) {
                            val id = _uiState.value.video?.id
                            if (id != null) putAspect(id, w to h)
                            val cur = _uiState.value
                            if (cur.videoWidth != w || cur.videoHeight != h) {
                                _uiState.update { it.copy(videoWidth = w, videoHeight = h) }
                            }
                        }
                    }
                    is PlayerEvent.Error -> handlePlaybackError(index, engine, event.message)
                    is PlayerEvent.Ended -> {
                        _uiState.update { it.copy(isPlaying = false, error = null) }
                        val id = _uiState.value.video?.id
                        if (id != null) {
                            viewModelScope.launch { playbackProgressDao.delete(id) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startPositionTicker(index: Int) {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                val engine = playerPool.get(index)
                if (engine != null && !scrubbing) {
                    val pos = engine.getPositionMs()
                    val dur = engine.getDurationMs()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now >= seekGuardUntilElapsedMs) {
                        _uiState.update {
                            it.copy(
                                positionMs = pos,
                                durationMs = if (dur > 0) dur else it.durationMs,
                                isPlaying = engine.isPlaying(),
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                durationMs = if (dur > 0) dur else it.durationMs,
                                isPlaying = engine.isPlaying(),
                            )
                        }
                    }
                    if (now - lastProgressPersistAtMs >= PROGRESS_PERSIST_INTERVAL_MS) {
                        lastProgressPersistAtMs = now
                        val videoId = _uiState.value.video?.id
                        if (videoId != null) {
                            persistProgress(
                                videoId,
                                _uiState.value.positionMs,
                                _uiState.value.durationMs,
                            )
                        }
                    }
                }
                kotlinx.coroutines.delay(if (scrubbing) 32 else 200)
            }
        }
    }

    private fun handlePlaybackError(index: Int, engine: PlayerEngine, message: String) {
        val snap = playlistSession.current() ?: return
        if (index !in snap.ids.indices) return
        val videoId = snap.ids[index]
        viewModelScope.launch {
            val entity = videoRepository.getById(videoId) ?: return@launch
            val media = entity.toMediaRef()
            if (engine.engineId == EngineId.EXO) {
                failoverTracker.markExoFailed(videoId)
                val resumeMs = _uiState.value.positionMs.coerceAtLeast(0L)
                val vlc = playerPool.failoverToVlc(index, media, surfaces[index], resumeMs)
                _uiState.update {
                    it.copy(
                        error = "硬解失败，已切换兼容播放",
                        engineLabel = "VLC",
                        showExternalAction = false,
                    )
                }
                bindEvents(index)
                vlc.play()
            } else {
                _uiState.update {
                    it.copy(
                        error = message.ifBlank { "当前格式可能不受支持" },
                        showExternalAction = true,
                        engineLabel = engine.engineId.name,
                    )
                }
            }
        }
    }

    fun openExternal() {
        val video = _uiState.value.video ?: return
        val ok = externalPlayerLauncher.open(video.toMediaRef())
        if (!ok) {
            _uiState.update { it.copy(error = "没有可用的外部播放器") }
        }
    }

    fun trySoftDecode() {
        val index = _uiState.value.currentIndex
        val id = playlistSession.idAt(index) ?: return
        viewModelScope.launch {
            val entity = videoRepository.getById(id) ?: return@launch
            failoverTracker.markExoFailed(id)
            val resumeMs = _uiState.value.positionMs.coerceAtLeast(0L)
            playerPool.failoverToVlc(index, entity.toMediaRef(), surfaces[index], resumeMs)
            _uiState.update {
                it.copy(error = null, showExternalAction = false, engineLabel = "VLC")
            }
            bindEvents(index)
        }
    }

    fun togglePlay() {
        val index = _uiState.value.currentIndex
        val engine = playerPool.get(index) ?: return
        if (engine.isPlaying()) {
            engine.pause()
            _uiState.update { it.copy(isPlaying = false, pausedByUser = true, uiVisible = true) }
        } else {
            val pos = engine.getPositionMs()
            val dur = engine.getDurationMs().takeIf { it > 0 } ?: _uiState.value.durationMs
            // 播到末尾时先回起点，避免 Exo ENDED / VLC EndReached 后 play 无效
            if (dur > 0 && pos >= (dur - 500).coerceAtLeast(0L)) {
                engine.seekTo(0L)
                _uiState.update { it.copy(positionMs = 0L) }
            }
            engine.play()
            _uiState.update { it.copy(isPlaying = true, pausedByUser = false) }
        }
    }

    fun seekTo(positionMs: Long) {
        val index = _uiState.value.currentIndex
        val dur = _uiState.value.durationMs
        val target = if (dur > 0) {
            positionMs.coerceIn(0L, dur)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        val engine = playerPool.get(index)
        if (engine is com.guopian.app.media.exo.ExoPlayerEngine) {
            engine.seekExact(target)
        } else {
            engine?.seekTo(target)
        }
        seekGuardUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + 450L
        _uiState.update { it.copy(positionMs = target, uiVisible = true) }
    }

    fun beginScrub() {
        if (!scrubbing) {
            scrubWasPlaying = _uiState.value.isPlaying
        }
        scrubbing = true
        showUi()
    }

    /** 拖动中：UI 立刻跟手，解码按节流预览 seek；不改播放/暂停态。 */
    fun scrubToFraction(fraction: Float) {
        if (!scrubbing) beginScrub()
        val dur = _uiState.value.durationMs
        if (dur <= 0L) return
        val target = (fraction.coerceIn(0f, 1f) * dur).toLong().coerceIn(0L, dur)
        val keepPlaying = scrubWasPlaying ?: _uiState.value.isPlaying
        _uiState.update { it.copy(positionMs = target, isPlaying = keepPlaying) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScrubSeekAtMs < 70L) return
        lastScrubSeekAtMs = now
        playerPool.get(_uiState.value.currentIndex)?.seekTo(target)
    }

    fun endScrub(fraction: Float) {
        val keepPlaying = scrubWasPlaying ?: _uiState.value.isPlaying
        scrubbing = false
        scrubWasPlaying = null
        val dur = _uiState.value.durationMs
        if (dur <= 0L) {
            _uiState.update { it.copy(isPlaying = keepPlaying) }
            showUi()
            return
        }
        val target = (fraction.coerceIn(0f, 1f) * dur).toLong().coerceIn(0L, dur)
        seekTo(target)
        val engine = playerPool.get(_uiState.value.currentIndex)
        if (keepPlaying) {
            engine?.play()
        } else {
            engine?.pause()
        }
        _uiState.update { it.copy(isPlaying = keepPlaying, pausedByUser = !keepPlaying) }
        val videoId = _uiState.value.video?.id
        if (videoId != null) {
            viewModelScope.launch {
                persistProgress(videoId, target, dur)
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val cur = _uiState.value.positionMs
        val dur = _uiState.value.durationMs
        val target = (cur + deltaMs).coerceAtLeast(0L).let {
            if (dur > 0) it.coerceAtMost(dur) else it
        }
        seekTo(target)
        showUi()
    }

    fun goPrevious() {
        val idx = _uiState.value.currentIndex
        if (idx > 0) {
            _uiState.update { it.copy(pendingScrollIndex = idx - 1, uiVisible = true) }
            return
        }
        val snap = playlistSession.current() ?: return
        if (!snap.hasPrevPage) return
        viewModelScope.launch {
            val result = playlistSession.openPage(snap.pageIndex - 1) ?: return@launch
            playerPool.releaseAll()
            failoverTracker.clear()
            val last = (result.snapshot.ids.size - 1).coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    ready = true,
                    currentIndex = last,
                    total = result.snapshot.ids.size,
                    pageIndex = result.snapshot.pageIndex,
                    pageCount = result.snapshot.pageCount,
                    totalInLibrary = result.snapshot.totalCount,
                    playlistEpoch = it.playlistEpoch + 1,
                    pendingScrollIndex = last,
                    uiVisible = true,
                )
            }
            onPageSettled(last)
        }
    }

    fun goNext() {
        val idx = _uiState.value.currentIndex
        if (idx + 1 < _uiState.value.total) {
            _uiState.update { it.copy(pendingScrollIndex = idx + 1, uiVisible = true) }
            return
        }
        val snap = playlistSession.current() ?: return
        if (!snap.hasNextPage) return
        viewModelScope.launch {
            val result = playlistSession.openPage(snap.pageIndex + 1) ?: return@launch
            playerPool.releaseAll()
            failoverTracker.clear()
            _uiState.update {
                it.copy(
                    ready = true,
                    currentIndex = 0,
                    total = result.snapshot.ids.size,
                    pageIndex = result.snapshot.pageIndex,
                    pageCount = result.snapshot.pageCount,
                    totalInLibrary = result.snapshot.totalCount,
                    playlistEpoch = it.playlistEpoch + 1,
                    pendingScrollIndex = 0,
                    uiVisible = true,
                )
            }
            onPageSettled(0)
        }
    }

    fun cycleSpeed() {
        val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val cur = _uiState.value.playbackSpeed
        val idx = options.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        val next = options[(idx + 1).coerceAtLeast(0) % options.size]
        setPlaybackSpeed(next)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3f)
        playerPool.get(_uiState.value.currentIndex)?.setSpeed(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        showUi()
    }

    fun speedBoost(pressed: Boolean) {
        val base = _uiState.value.playbackSpeed
        playerPool.get(_uiState.value.currentIndex)?.setSpeed(if (pressed) 2f else base)
    }

    fun toggleUi() {
        _uiState.update { it.copy(uiVisible = !it.uiVisible) }
    }

    fun showUi() {
        _uiState.update { it.copy(uiVisible = true, controlsEpoch = it.controlsEpoch + 1) }
    }

    fun hideUi() {
        _uiState.update { it.copy(uiVisible = false) }
    }

    fun doubleTapKeep() {
        toggleKeep()
    }

    /** 心形：点亮=收藏；再点取消 → 默认为待删除。 */
    fun toggleKeep() {
        val current = _uiState.value.markStatus
        val next = if (current == MarkStatus.KEEP) MarkStatus.TO_DELETE else MarkStatus.KEEP
        // 先发特效，再落库；双击也能立刻看到反馈
        _uiState.update {
            it.copy(
                keepBurstEpoch = it.keepBurstEpoch + 1,
                keepBurstKept = next == MarkStatus.KEEP,
                markStatus = next,
                uiVisible = true,
                controlsEpoch = it.controlsEpoch + 1,
            )
        }
        setStatus(next, advanceIfEnabled = next == MarkStatus.KEEP)
    }

    /** 待定：点亮=待定；再点取消 → 默认为待删除。 */
    fun togglePending() {
        val current = _uiState.value.markStatus
        val next = if (current == MarkStatus.PENDING) MarkStatus.TO_DELETE else MarkStatus.PENDING
        setStatus(next, advanceIfEnabled = false)
    }

    fun setStatus(status: MarkStatus) {
        setStatus(status, advanceIfEnabled = false)
    }

    private fun setStatus(status: MarkStatus, advanceIfEnabled: Boolean) {
        val video = _uiState.value.video ?: return
        viewModelScope.launch {
            markVideoUseCase.setStatus(listOf(video.id), status)
            if (status == MarkStatus.KEEP) {
                markVideoUseCase.setStar(listOf(video.id), 1)
            } else {
                markVideoUseCase.setStar(listOf(video.id), 0)
            }
            val root = rootFolderRepository.getCurrent()
            if (root != null) {
                withContext(Dispatchers.IO) {
                    browseStateStore.upsertMark(
                        Uri.parse(root.treeUri),
                        video.relativePath,
                        status,
                    )
                }
            }
            val updated = videoRepository.getById(video.id)
            var pendingScroll: Int? = null
            var jumpNextPage = false
            if (advanceIfEnabled) {
                val autoNext = settingsRepository.markAutoNext.first()
                val idx = _uiState.value.currentIndex
                if (autoNext) {
                    when {
                        idx + 1 < _uiState.value.total -> pendingScroll = idx + 1
                        playlistSession.current()?.hasNextPage == true -> jumpNextPage = true
                    }
                }
            }
            _uiState.update {
                it.copy(
                    video = updated,
                    markStatus = status,
                    pendingScrollIndex = pendingScroll ?: it.pendingScrollIndex,
                )
            }
            if (jumpNextPage) goNext()
        }
    }

    fun consumePendingScroll() {
        _uiState.update { it.copy(pendingScrollIndex = null) }
    }

    fun openCutSheet() {
        if (_uiState.value.video == null) return
        _uiState.update { it.copy(showCutSheet = true) }
    }

    fun dismissCutSheet() {
        _uiState.update { it.copy(showCutSheet = false) }
    }

    fun setConflictPolicy(policy: ConflictPolicy) {
        _uiState.update { it.copy(conflictPolicy = policy) }
    }

    fun clearCutMessage() {
        _uiState.update { it.copy(cutMessage = null) }
    }

    fun enqueueCut(targetTreeUri: Uri) {
        val id = _uiState.value.video?.id ?: return
        val policy = _uiState.value.conflictPolicy
        viewModelScope.launch {
            val taskId = moveScheduler.enqueueMove(listOf(id), targetTreeUri, policy)
            _uiState.update {
                it.copy(
                    showCutSheet = false,
                    cutMessage = "已加入剪切任务 #$taskId",
                )
            }
        }
    }

    fun enqueueCutPreset(preset: FolderPresetEntity) {
        enqueueCut(Uri.parse(preset.uri))
    }

    fun engineForPage(index: Int): PlayerEngine? = playerPool.getOrNull(index)

    fun currentVideoId(): Long? = _uiState.value.video?.id

    override fun onCleared() {
        val videoId = _uiState.value.video?.id
        val pos = _uiState.value.positionMs
        val dur = _uiState.value.durationMs
        eventJob?.cancel()
        positionJob?.cancel()
        settleJob?.cancel()
        aspectJobs.values.forEach { it.cancel() }
        aspectJobs.clear()
        if (videoId != null) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(800L) {
                        persistProgress(videoId, pos, dur)
                    }
                }
            }
        }
        playerPool.releaseAll()
        failoverTracker.clear()
        surfaces.clear()
        super.onCleared()
    }

    private suspend fun resumePositionFor(videoId: Long): Long {
        val saved = playbackProgressDao.get(videoId) ?: return 0L
        val pos = saved.positionMs
        if (pos < MIN_RESUME_MS) return 0L
        val entityDur = videoRepository.getById(videoId)?.durationMs?.takeIf { it > 0 } ?: 0L
        if (entityDur > 0L && pos >= entityDur - NEAR_END_MS) {
            playbackProgressDao.delete(videoId)
            return 0L
        }
        return pos
    }

    private suspend fun persistProgress(videoId: Long, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_RESUME_MS) {
            playbackProgressDao.delete(videoId)
            return
        }
        if (durationMs > 0L && positionMs >= durationMs - NEAR_END_MS) {
            playbackProgressDao.delete(videoId)
            return
        }
        playbackProgressDao.upsert(
            PlaybackProgressEntity(
                videoId = videoId,
                positionMs = positionMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        private const val MIN_RESUME_MS = 2_000L
        private const val NEAR_END_MS = 2_500L
        private const val PROGRESS_PERSIST_INTERVAL_MS = 2_000L
    }
}

fun VideoEntity.toMediaRef(): MediaRef = MediaRef(
    videoId = id,
    uri = Uri.parse(docUri),
    mimeType = mime,
    fileName = fileName,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
)

private fun displaySizeOf(video: VideoEntity?): Pair<Int, Int> {
    if (video == null) return 0 to 0
    return displaySize(video.width, video.height, video.rotation)
}

private fun displaySize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val swapped = rotationDegrees % 180 != 0
    return if (swapped) height to width else width to height
}
