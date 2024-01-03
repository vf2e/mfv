package com.guopian.app.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.guopian.app.data.db.VideoEntity
import com.guopian.app.data.prefs.SettingsRepository
import com.guopian.app.data.repo.RootBrowseStateStore
import com.guopian.app.data.repo.RootFolderRepository
import com.guopian.app.data.repo.VideoRepository
import com.guopian.app.data.db.FolderPresetDao
import com.guopian.app.data.db.FolderPresetEntity
import com.guopian.app.domain.model.ConflictPolicy
import com.guopian.app.domain.model.FilterSpec
import com.guopian.app.domain.model.MarkStatus
import com.guopian.app.domain.model.SortKey
import com.guopian.app.domain.model.SortSpec
import com.guopian.app.domain.usecase.MarkVideoUseCase
import com.guopian.app.player.playlist.PlaylistSession
import com.guopian.app.work.MoveScheduler
import com.guopian.app.work.ScanScheduler
import com.guopian.app.work.ScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class QuickFilter {
    ALL,
    UNSORTED,
    KEEP,
    PENDING,
    TO_DELETE,
    MOVED,
}

data class LibraryUiState(
    val hasRoot: Boolean = false,
    val rootId: Long? = null,
    val rootPath: String? = null,
    val videoCount: Int = 0,
    val isScanning: Boolean = false,
    val scanFound: Int = 0,
    val quickFilter: QuickFilter = QuickFilter.ALL,
    val pathKeyword: String = "",
    val queryText: String = "",
    val filterExtensions: Set<String> = emptySet(),
    val filterParentPath: String? = null,
    val filterTagId: Long? = null,
    val markOverride: Set<MarkStatus>? = null,
    val externalFilterLabel: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val sort: SortSpec = SortSpec(),
    val showCutSheet: Boolean = false,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
    val cutMessage: String? = null,
    val scanError: String? = null,
    val scanResultMessage: String? = null,
    val lastRelativePath: String? = null,
    val pageSize: Int = 100,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootFolderRepository: RootFolderRepository,
    private val videoRepository: VideoRepository,
    private val scanScheduler: ScanScheduler,
    private val markVideoUseCase: MarkVideoUseCase,
    private val playlistSession: PlaylistSession,
    private val moveScheduler: MoveScheduler,
    private val folderPresetDao: FolderPresetDao,
    private val filterBridge: LibraryFilterBridge,
    private val tagDao: com.guopian.app.data.db.TagDao,
    private val settingsRepository: SettingsRepository,
    private val browseStateStore: RootBrowseStateStore,
) : ViewModel() {

    private val rootFlow = rootFolderRepository.observeCurrent()
    private val filterState = MutableStateFlow(LibraryUiState())

    val folderPresets: StateFlow<List<FolderPresetEntity>> = folderPresetDao.observeRecent()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val tags: StateFlow<List<com.guopian.app.data.db.TagEntity>> = tagDao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val scanInfoFlow = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(ScanWorker.UNIQUE_WORK)
        .map { infos -> infos.firstOrNull() }

    private val randomSeed = System.currentTimeMillis()
    private val lastRelativePath = MutableStateFlow<String?>(null)
    private val pageSizeFlow = settingsRepository.pageSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)

    @OptIn(ExperimentalCoroutinesApi::class)
    val videos: Flow<PagingData<VideoEntity>> = combine(
        rootFlow,
        filterState,
        pageSizeFlow,
    ) { root, ui, pageSize ->
        Triple(root, ui, pageSize)
    }.flatMapLatest { (root, ui, pageSize) ->
        if (root == null) {
            flowOf(PagingData.empty())
        } else {
            videoRepository.pagingVideos(
                rootId = root.id,
                filter = ui.toFilterSpec(),
                sort = ui.sort,
                randomSeed = randomSeed,
                pageSize = pageSize,
            )
        }
    }.cachedIn(viewModelScope)

    private val filteredCount = MutableStateFlow(0)
    private val scanResultMessage = MutableStateFlow<String?>(null)
    private var lastHandledScanId: UUID? = null

    init {
        viewModelScope.launch {
            rootFlow.collect { root ->
                if (root == null) {
                    lastRelativePath.value = null
                } else {
                    lastRelativePath.value = browseStateStore.read(Uri.parse(root.treeUri)).lastRelativePath
                }
            }
        }
        viewModelScope.launch {
            combine(rootFlow, filterState) { root, ui -> root to ui }.collect { (root, ui) ->
                if (root == null) {
                    filteredCount.value = 0
                } else {
                    filteredCount.value = videoRepository.countFiltered(root.id, ui.toFilterSpec())
                }
            }
        }
        viewModelScope.launch {
            filterBridge.pending.collect { request ->
                if (request != null) {
                    applyBrowseRequest(request)
                    filterBridge.consume()
                }
            }
        }
        viewModelScope.launch {
            scanInfoFlow.collect { work ->
                if (work == null) return@collect
                if (work.state == WorkInfo.State.SUCCEEDED && work.id != lastHandledScanId) {
                    lastHandledScanId = work.id
                    val n = work.outputData.getInt(ScanWorker.KEY_VIDEO_COUNT, 0)
                    val folder = work.outputData.getString(ScanWorker.KEY_FOLDER).orEmpty().ifBlank { "所选文件夹" }
                    scanResultMessage.value = if (n <= 0) {
                        "「$folder」内未发现视频。请改选实际有视频的目录（如 Movies），不要选空的 DCIM。"
                    } else {
                        "扫描完成：在「$folder」发现 $n 部视频"
                    }
                    // refresh filtered count after DB write
                    val root = rootFolderRepository.getCurrent()
                    if (root != null) {
                        filteredCount.value = videoRepository.countFiltered(
                            root.id,
                            filterState.value.toFilterSpec(),
                        )
                    }
                } else if (work.state == WorkInfo.State.FAILED && work.id != lastHandledScanId) {
                    lastHandledScanId = work.id
                    scanResultMessage.value = work.outputData.getString(ScanWorker.KEY_ERROR)
                        ?: "扫描失败，请重新选择文件夹"
                }
            }
        }
    }

    private fun applyBrowseRequest(request: LibraryBrowseRequest) {
        filterState.update {
            when (request) {
                is LibraryBrowseRequest.ByMarks -> it.copy(
                    quickFilter = QuickFilter.ALL,
                    pathKeyword = "",
                    queryText = "",
                    filterExtensions = emptySet(),
                    filterParentPath = null,
                    filterTagId = null,
                    externalFilterLabel = request.label,
                    markOverride = request.statuses,
                )
                is LibraryBrowseRequest.ByExtension -> it.copy(
                    quickFilter = QuickFilter.ALL,
                    pathKeyword = "",
                    queryText = "",
                    filterExtensions = setOf(request.ext.lowercase()),
                    filterParentPath = null,
                    filterTagId = null,
                    externalFilterLabel = request.label,
                    markOverride = null,
                )
                is LibraryBrowseRequest.ByFolder -> it.copy(
                    quickFilter = QuickFilter.ALL,
                    pathKeyword = "",
                    queryText = "",
                    filterExtensions = emptySet(),
                    filterParentPath = request.parentSubPath,
                    filterTagId = null,
                    externalFilterLabel = request.label,
                    markOverride = null,
                )
                is LibraryBrowseRequest.ByTag -> it.copy(
                    quickFilter = QuickFilter.ALL,
                    pathKeyword = "",
                    queryText = "",
                    filterExtensions = emptySet(),
                    filterParentPath = null,
                    filterTagId = request.tagId,
                    externalFilterLabel = request.label,
                    markOverride = null,
                )
            }
        }
    }

    fun clearExternalFilter() {
        filterState.update {
            it.copy(
                filterExtensions = emptySet(),
                filterParentPath = null,
                filterTagId = null,
                externalFilterLabel = null,
                markOverride = null,
            )
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        combine(rootFlow, filteredCount, scanInfoFlow) { root, count, work ->
            Triple(root, count, work)
        },
        combine(filterState, scanResultMessage, lastRelativePath, pageSizeFlow) { filter, scanMsg, last, pageSize ->
            LibraryExtras(filter, scanMsg, last, pageSize)
        },
    ) { rootCountWork, extras ->
        val (root, count, work) = rootCountWork
        val running = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
        val found = work?.progress?.getInt(ScanWorker.KEY_FOUND, 0) ?: 0
        val scanError = if (work?.state == WorkInfo.State.FAILED) {
            work.outputData.getString(ScanWorker.KEY_ERROR) ?: "扫描失败"
        } else {
            null
        }
        extras.filter.copy(
            hasRoot = root != null,
            rootId = root?.id,
            rootPath = root?.displayPath,
            videoCount = count,
            isScanning = running,
            scanFound = found,
            scanError = scanError,
            scanResultMessage = extras.scanMsg,
            lastRelativePath = extras.lastPath,
            pageSize = extras.pageSize,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    private data class LibraryExtras(
        val filter: LibraryUiState,
        val scanMsg: String?,
        val lastPath: String?,
        val pageSize: Int,
    )

    fun onRootSelected(uri: Uri) {
        viewModelScope.launch {
            rootFolderRepository.setRootTreeUri(uri)
            scanScheduler.enqueueFullScan()
        }
    }

    fun onRootFilePathSelected(absolutePath: String) {
        viewModelScope.launch {
            rootFolderRepository.setRootFilePath(absolutePath)
            scanScheduler.enqueueFullScan()
        }
    }

    fun scanNow() {
        scanScheduler.enqueueFullScan()
    }

    fun setQuickFilter(filter: QuickFilter) {
        filterState.update {
            it.copy(
                quickFilter = filter,
                markOverride = null,
                filterExtensions = emptySet(),
                filterParentPath = null,
                filterTagId = null,
                externalFilterLabel = null,
            )
        }
    }

    fun tagSelected(tagId: Long) {
        val ids = filterState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { videoId ->
                tagDao.link(com.guopian.app.data.db.VideoTagCrossRef(videoId = videoId, tagId = tagId))
            }
            clearSelection()
        }
    }

    fun setPathKeyword(keyword: String) {
        filterState.update { it.copy(pathKeyword = keyword) }
    }

    fun setQueryText(text: String) {
        filterState.update { it.copy(queryText = text) }
    }

    fun setSort(key: SortKey) {
        filterState.update {
            val asc = if (it.sort.key == key) !it.sort.ascending else false
            it.copy(sort = SortSpec(key, asc))
        }
    }

    fun enterSelection(videoId: Long) {
        filterState.update {
            it.copy(selectionMode = true, selectedIds = setOf(videoId))
        }
    }

    fun toggleSelect(videoId: Long) {
        filterState.update { state ->
            if (!state.selectionMode) {
                state.copy(selectionMode = true, selectedIds = setOf(videoId))
            } else {
                val next = state.selectedIds.toMutableSet()
                if (!next.add(videoId)) next.remove(videoId)
                state.copy(selectedIds = next, selectionMode = next.isNotEmpty())
            }
        }
    }

    fun clearSelection() {
        filterState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun markSelected(status: MarkStatus) {
        val ids = filterState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            markVideoUseCase.setStatus(ids, status)
            val root = rootFolderRepository.getCurrent()
            if (root != null) {
                val treeUri = Uri.parse(root.treeUri)
                ids.forEach { id ->
                    val video = videoRepository.getById(id) ?: return@forEach
                    browseStateStore.upsertMark(treeUri, video.relativePath, status)
                }
            }
            clearSelection()
        }
    }

    fun openCutSheet() {
        if (filterState.value.selectedIds.isEmpty()) return
        filterState.update { it.copy(showCutSheet = true) }
    }

    fun dismissCutSheet() {
        filterState.update { it.copy(showCutSheet = false) }
    }

    fun setConflictPolicy(policy: ConflictPolicy) {
        filterState.update { it.copy(conflictPolicy = policy) }
    }

    fun clearCutMessage() {
        filterState.update { it.copy(cutMessage = null) }
    }

    fun clearScanResultMessage() {
        scanResultMessage.value = null
    }

    fun enqueueCut(targetTreeUri: Uri) {
        val ids = filterState.value.selectedIds.toList()
        val policy = filterState.value.conflictPolicy
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val taskId = moveScheduler.enqueueMove(ids, targetTreeUri, policy)
            filterState.update {
                it.copy(
                    showCutSheet = false,
                    selectionMode = false,
                    selectedIds = emptySet(),
                    cutMessage = "已加入剪切任务 #$taskId",
                )
            }
        }
    }

    fun enqueueCutPreset(preset: FolderPresetEntity) {
        enqueueCut(Uri.parse(preset.uri))
    }

    /** 从第一条未标记（若无则全部第一条）进入抖音式过片。 */
    suspend fun startBrowse(): Int? {
        val rootId = rootFolderRepository.getCurrent()?.id ?: return null
        val pageSize = settingsRepository.pageSize.first()
        val unsorted = videoRepository.queryIds(
            rootId = rootId,
            filter = FilterSpec(markStatuses = setOf(MarkStatus.UNSORTED, MarkStatus.TO_DELETE)),
            sort = filterState.value.sort,
            randomSeed = randomSeed,
            limit = 1,
            offset = 0,
        )
        val startId = unsorted.firstOrNull()
            ?: videoRepository.queryIds(
                rootId = rootId,
                filter = FilterSpec(),
                sort = filterState.value.sort,
                randomSeed = randomSeed,
                limit = 1,
                offset = 0,
            ).firstOrNull()
            ?: return null
        val result = playlistSession.open(
            rootId = rootId,
            filter = FilterSpec(),
            sort = filterState.value.sort,
            startVideoId = startId,
            randomSeed = randomSeed,
            pageSize = pageSize,
        )
        return result.startIndex
    }

    /** 一键跳到目录 txt 记录的上次浏览位置。 */
    suspend fun resumeLastBrowse(): Int? {
        val root = rootFolderRepository.getCurrent() ?: return null
        val last = browseStateStore.read(Uri.parse(root.treeUri)).lastRelativePath
            ?: lastRelativePath.value
            ?: return null
        val videoId = videoRepository.findIdByRelativePath(root.id, last) ?: return null
        lastRelativePath.value = last
        return openSwipe(videoId)
    }

    /** 选中当前筛选列表全部视频并打开剪切面板。 */
    fun prepareCutCurrentFilter() {
        viewModelScope.launch {
            val rootId = rootFolderRepository.getCurrent()?.id ?: return@launch
            val ui = filterState.value
            val ids = videoRepository.queryIds(
                rootId = rootId,
                filter = ui.toFilterSpec(),
                sort = ui.sort,
                randomSeed = randomSeed,
            )
            if (ids.isEmpty()) {
                filterState.update { it.copy(cutMessage = "当前筛选下没有可剪切的视频") }
                return@launch
            }
            filterState.update {
                it.copy(
                    selectionMode = true,
                    selectedIds = ids.toSet(),
                    showCutSheet = true,
                )
            }
        }
    }

    /** 一键选中全部「收藏」并打开剪切。 */
    fun prepareCutKept() {
        filterState.update { it.copy(quickFilter = QuickFilter.KEEP) }
        viewModelScope.launch {
            val rootId = rootFolderRepository.getCurrent()?.id ?: return@launch
            val ids = videoRepository.queryIds(
                rootId = rootId,
                filter = FilterSpec(markStatuses = setOf(MarkStatus.KEEP)),
                sort = filterState.value.sort,
                randomSeed = randomSeed,
            )
            if (ids.isEmpty()) {
                filterState.update { it.copy(cutMessage = "还没有标记为「收藏」的视频") }
                return@launch
            }
            filterState.update {
                it.copy(
                    selectionMode = true,
                    selectedIds = ids.toSet(),
                    showCutSheet = true,
                    quickFilter = QuickFilter.KEEP,
                )
            }
        }
    }

    suspend fun openSwipe(startVideoId: Long): Int? {
        val rootId = rootFolderRepository.getCurrent()?.id ?: return null
        val ui = filterState.value
        val pageSize = settingsRepository.pageSize.first()
        val result = playlistSession.open(
            rootId = rootId,
            filter = ui.toFilterSpec(),
            sort = ui.sort,
            startVideoId = startVideoId,
            randomSeed = randomSeed,
            pageSize = pageSize,
        )
        return result.startIndex
    }

    fun refreshLastCursor() {
        viewModelScope.launch {
            val root = rootFolderRepository.getCurrent() ?: return@launch
            lastRelativePath.value = browseStateStore.read(Uri.parse(root.treeUri)).lastRelativePath
        }
    }
}

private fun LibraryUiState.toFilterSpec(): FilterSpec {
    val statuses = markOverride ?: when (quickFilter) {
        QuickFilter.ALL -> emptySet()
        // 未收藏/未待定默认即待删除；兼容旧数据 UNSORTED
        QuickFilter.UNSORTED -> setOf(MarkStatus.UNSORTED, MarkStatus.TO_DELETE)
        QuickFilter.KEEP -> setOf(MarkStatus.KEEP)
        QuickFilter.PENDING -> setOf(MarkStatus.PENDING)
        QuickFilter.TO_DELETE -> setOf(MarkStatus.TO_DELETE, MarkStatus.UNSORTED)
        QuickFilter.MOVED -> setOf(MarkStatus.MOVED)
    }
    return FilterSpec(
        pathContains = pathKeyword.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty(),
        parentSubPaths = filterParentPath?.let { listOf(it) }.orEmpty(),
        markStatuses = statuses,
        extensions = filterExtensions,
        tagId = filterTagId,
        onlyUnmarked = false,
        queryText = queryText.trim().takeIf { it.isNotEmpty() },
    )
}
