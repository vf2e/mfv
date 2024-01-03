package com.guopian.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "guopian_settings")

enum class DoubleTapAction { KEEP, PENDING, TO_DELETE, TOGGLE_PLAY }
enum class EndBehavior { NEXT, LOOP, STOP }
enum class PlayerPrefer { EXO_THEN_VLC, VLC_FIRST, EXTERNAL }

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val preloadCount = intPreferencesKey("preload_count")
        val pageSize = intPreferencesKey("browse_page_size")
        val doubleTapAction = stringPreferencesKey("double_tap_action")
        val swapSwipe = booleanPreferencesKey("swap_swipe")
        val endBehavior = stringPreferencesKey("end_behavior")
        val markAutoNext = booleanPreferencesKey("mark_auto_next")
        val playerPrefer = stringPreferencesKey("player_prefer")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val showTagsTab = booleanPreferencesKey("show_tags_tab")
    }

    val preloadCount: Flow<Int> = context.settingsDataStore.data.map {
        it[Keys.preloadCount] ?: 1
    }

    /** 过片/列表分页大小，默认 100。 */
    val pageSize: Flow<Int> = context.settingsDataStore.data.map {
        (it[Keys.pageSize] ?: 100).coerceIn(20, 500)
    }

    val markAutoNext: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.markAutoNext] ?: false
    }

    val swapSwipe: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.swapSwipe] ?: false
    }

    /** 底栏「标签」页，默认关闭。 */
    val showTagsTab: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.showTagsTab] ?: false
    }

    val doubleTapAction: Flow<DoubleTapAction> = context.settingsDataStore.data.map {
        runCatching { DoubleTapAction.valueOf(it[Keys.doubleTapAction] ?: "KEEP") }
            .getOrDefault(DoubleTapAction.KEEP)
    }

    val endBehavior: Flow<EndBehavior> = context.settingsDataStore.data.map {
        runCatching { EndBehavior.valueOf(it[Keys.endBehavior] ?: "NEXT") }
            .getOrDefault(EndBehavior.NEXT)
    }

    val playerPrefer: Flow<PlayerPrefer> = context.settingsDataStore.data.map {
        runCatching { PlayerPrefer.valueOf(it[Keys.playerPrefer] ?: "EXO_THEN_VLC") }
            .getOrDefault(PlayerPrefer.EXO_THEN_VLC)
    }

    val onboardingDone: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.onboardingDone] ?: false
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsDataStore.edit { it[Keys.onboardingDone] = done }
    }

    suspend fun setPreloadCount(count: Int) {
        context.settingsDataStore.edit { it[Keys.preloadCount] = count.coerceIn(1, 3) }
    }

    suspend fun setPageSize(size: Int) {
        context.settingsDataStore.edit { it[Keys.pageSize] = size.coerceIn(20, 500) }
    }

    suspend fun setMarkAutoNext(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.markAutoNext] = enabled }
    }

    suspend fun setSwapSwipe(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.swapSwipe] = enabled }
    }

    suspend fun setDoubleTapAction(action: DoubleTapAction) {
        context.settingsDataStore.edit { it[Keys.doubleTapAction] = action.name }
    }

    suspend fun setEndBehavior(behavior: EndBehavior) {
        context.settingsDataStore.edit { it[Keys.endBehavior] = behavior.name }
    }

    suspend fun setPlayerPrefer(value: PlayerPrefer) {
        context.settingsDataStore.edit { it[Keys.playerPrefer] = value.name }
    }

    suspend fun setShowTagsTab(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.showTagsTab] = enabled }
    }
}
