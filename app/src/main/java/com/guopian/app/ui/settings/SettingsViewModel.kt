package com.guopian.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guopian.app.data.prefs.DoubleTapAction
import com.guopian.app.data.prefs.EndBehavior
import com.guopian.app.data.prefs.PlayerPrefer
import com.guopian.app.data.prefs.SettingsRepository
import com.guopian.app.data.repo.RootFolderRepository
import com.guopian.app.work.ScanScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val rootPath: String? = null,
    val videoCount: Int = 0,
    val lastScannedAt: Long = 0L,
    val preloadCount: Int = 1,
    val pageSize: Int = 100,
    val markAutoNext: Boolean = false,
    val swapSwipe: Boolean = false,
    val showTagsTab: Boolean = false,
    val doubleTapAction: DoubleTapAction = DoubleTapAction.KEEP,
    val endBehavior: EndBehavior = EndBehavior.NEXT,
    val playerPrefer: PlayerPrefer = PlayerPrefer.EXO_THEN_VLC,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val rootFolderRepository: RootFolderRepository,
    private val settingsRepository: SettingsRepository,
    private val scanScheduler: ScanScheduler,
) : ViewModel() {

    private val gesturePrefs = combine(
        settingsRepository.markAutoNext,
        settingsRepository.swapSwipe,
        settingsRepository.doubleTapAction,
        settingsRepository.showTagsTab,
    ) { autoNext, swap, doubleTap, showTags ->
        GesturePrefs(autoNext, swap, doubleTap, showTags)
    }

    private val playPrefs = combine(
        settingsRepository.preloadCount,
        settingsRepository.pageSize,
        settingsRepository.endBehavior,
        settingsRepository.playerPrefer,
    ) { preload, pageSize, end, player ->
        PlayPrefs(preload, pageSize, end, player)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        rootFolderRepository.observeCurrent(),
        gesturePrefs,
        playPrefs,
    ) { root, gesture, play ->
        SettingsUiState(
            rootPath = root?.displayPath,
            videoCount = root?.videoCount ?: 0,
            lastScannedAt = root?.lastScannedAt ?: 0L,
            preloadCount = play.preload,
            pageSize = play.pageSize,
            markAutoNext = gesture.markAutoNext,
            swapSwipe = gesture.swapSwipe,
            showTagsTab = gesture.showTagsTab,
            doubleTapAction = gesture.doubleTap,
            endBehavior = play.endBehavior,
            playerPrefer = play.playerPrefer,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun changeRoot(uri: Uri) {
        viewModelScope.launch {
            rootFolderRepository.setRootTreeUri(uri)
            scanScheduler.enqueueFullScan()
        }
    }

    fun changeRootFilePath(absolutePath: String) {
        viewModelScope.launch {
            rootFolderRepository.setRootFilePath(absolutePath)
            scanScheduler.enqueueFullScan()
        }
    }

    fun scanNow() = scanScheduler.enqueueFullScan()

    fun setPreloadCount(count: Int) {
        viewModelScope.launch { settingsRepository.setPreloadCount(count) }
    }

    fun setPageSize(size: Int) {
        viewModelScope.launch { settingsRepository.setPageSize(size) }
    }

    fun setMarkAutoNext(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMarkAutoNext(enabled) }
    }

    fun setSwapSwipe(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSwapSwipe(enabled) }
    }

    fun setDoubleTapAction(action: DoubleTapAction) {
        viewModelScope.launch { settingsRepository.setDoubleTapAction(action) }
    }

    fun setEndBehavior(behavior: EndBehavior) {
        viewModelScope.launch { settingsRepository.setEndBehavior(behavior) }
    }

    fun setPlayerPrefer(prefer: PlayerPrefer) {
        viewModelScope.launch { settingsRepository.setPlayerPrefer(prefer) }
    }

    fun setShowTagsTab(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowTagsTab(enabled) }
    }
}

private data class GesturePrefs(
    val markAutoNext: Boolean,
    val swapSwipe: Boolean,
    val doubleTap: DoubleTapAction,
    val showTagsTab: Boolean,
)

private data class PlayPrefs(
    val preload: Int,
    val pageSize: Int,
    val endBehavior: EndBehavior,
    val playerPrefer: PlayerPrefer,
)
