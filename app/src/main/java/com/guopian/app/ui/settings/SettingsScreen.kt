package com.guopian.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.guopian.app.BuildConfig
import com.guopian.app.R
import com.guopian.app.core.util.StorageAccessHelper
import com.guopian.app.data.prefs.DoubleTapAction
import com.guopian.app.data.prefs.EndBehavior
import com.guopian.app.data.prefs.PlayerPrefer
import com.guopian.app.ui.components.GuopianHairline
import com.guopian.app.ui.components.GuopianPageHeader
import com.guopian.app.ui.components.GuopianSegmentedRail
import com.guopian.app.ui.components.GuopianStepPicker
import com.guopian.app.ui.theme.GuopianColors
import com.guopian.app.ui.theme.GuopianDimens
import com.guopian.app.ui.library.FileFolderPickerDialog
import com.guopian.app.ui.theme.guopianCard
import com.guopian.app.ui.util.OpenDocumentTreeContract
import com.guopian.app.ui.util.launchDocumentTreePreferAndroid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.LaunchedEffect

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAllFiles by remember { mutableStateOf(StorageAccessHelper.hasAllFilesAccess()) }
    var showFileFolderPicker by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasAllFiles = StorageAccessHelper.hasAllFilesAccess()
        }
    }
    val openTree = rememberLauncherForActivityResult(
        contract = OpenDocumentTreeContract(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.changeRoot(uri)
    }
    if (showFileFolderPicker) {
        FileFolderPickerDialog(
            onDismiss = { showFileFolderPicker = false },
            onConfirm = { path ->
                showFileFolderPicker = false
                viewModel.changeRootFilePath(path)
            },
        )
    }
    val scannedLabel = if (ui.lastScannedAt > 0L) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ui.lastScannedAt))
    } else {
        stringResource(R.string.settings_never_scanned)
    }

    // LazyColumn — 可滚动；勿用 fillMaxSize+Column+verticalScroll（易被底栏裁切且滚不动）
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(GuopianColors.Canvas),
        contentPadding = PaddingValues(bottom = GuopianDimens.Module),
    ) {
        item {
            GuopianPageHeader(
                title = stringResource(R.string.nav_settings),
                subtitle = stringResource(R.string.settings_subtitle),
            )
        }

        item { SectionLabel(stringResource(R.string.settings_section_library)) }
        item {
            Panel {
                LinkRow(
                    title = stringResource(R.string.settings_root_folder),
                    subtitle = ui.rootPath ?: stringResource(R.string.root_path_unset),
                    onClick = {
                        if (StorageAccessHelper.hasAllFilesAccess()) {
                            showFileFolderPicker = true
                        } else {
                            openTree.launchDocumentTreePreferAndroid()
                        }
                    },
                )
                GuopianHairline()
                LinkRow(
                    title = stringResource(R.string.settings_all_files_access),
                    subtitle = if (hasAllFiles) {
                        stringResource(R.string.settings_all_files_on)
                    } else {
                        stringResource(R.string.settings_all_files_off)
                    },
                    onClick = {
                        runCatching {
                            context.startActivity(StorageAccessHelper.manageAllFilesIntent(context))
                        }
                        hasAllFiles = StorageAccessHelper.hasAllFilesAccess()
                    },
                )
                GuopianHairline()
                LinkRow(
                    title = stringResource(R.string.action_scan),
                    subtitle = stringResource(R.string.settings_scan_detail, ui.videoCount, scannedLabel),
                    onClick = viewModel::scanNow,
                    chevron = false,
                    actionHint = stringResource(R.string.settings_tap_to_run),
                )
                GuopianHairline()
                ValueRow(
                    title = stringResource(R.string.settings_library_count),
                    value = stringResource(R.string.settings_library_count_value, ui.videoCount),
                )
                GuopianHairline()
                SwitchRow(
                    title = stringResource(R.string.settings_show_tags_tab),
                    subtitle = stringResource(R.string.settings_show_tags_tab_hint),
                    checked = ui.showTagsTab,
                    onCheckedChange = viewModel::setShowTagsTab,
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_section_playback)) }
        item {
            Panel {
                FieldBlock(
                    title = stringResource(R.string.settings_player_engine),
                    subtitle = stringResource(R.string.settings_player_engine_hint),
                ) {
                    GuopianSegmentedRail(
                        options = listOf(
                            PlayerPrefer.EXO_THEN_VLC to stringResource(R.string.settings_player_exo_first),
                            PlayerPrefer.VLC_FIRST to stringResource(R.string.settings_player_vlc_first),
                            PlayerPrefer.EXTERNAL to stringResource(R.string.settings_player_external),
                        ),
                        selected = ui.playerPrefer,
                        onSelect = viewModel::setPlayerPrefer,
                    )
                }
                GuopianHairline()
                FieldBlock(
                    title = stringResource(R.string.settings_end_behavior),
                    subtitle = stringResource(R.string.settings_end_behavior_hint),
                ) {
                    GuopianSegmentedRail(
                        options = listOf(
                            EndBehavior.NEXT to stringResource(R.string.settings_end_next),
                            EndBehavior.LOOP to stringResource(R.string.settings_end_loop),
                            EndBehavior.STOP to stringResource(R.string.settings_end_stop),
                        ),
                        selected = ui.endBehavior,
                        onSelect = viewModel::setEndBehavior,
                    )
                }
                GuopianHairline()
                FieldBlock(
                    title = stringResource(R.string.settings_preload_title),
                    subtitle = stringResource(R.string.settings_preload_hint),
                ) {
                    GuopianStepPicker(
                        value = ui.preloadCount,
                        range = 1..3,
                        onSelect = viewModel::setPreloadCount,
                        captions = mapOf(
                            1 to stringResource(R.string.settings_preload_cap_1),
                            2 to stringResource(R.string.settings_preload_cap_2),
                            3 to stringResource(R.string.settings_preload_cap_3),
                        ),
                    )
                }
                GuopianHairline()
                FieldBlock(
                    title = stringResource(R.string.settings_page_size),
                    subtitle = stringResource(R.string.settings_page_size_hint),
                ) {
                    GuopianSegmentedRail(
                        options = listOf(
                            50 to "50",
                            100 to "100",
                            200 to "200",
                            500 to "500",
                        ),
                        selected = ui.pageSize,
                        onSelect = viewModel::setPageSize,
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.settings_section_gesture)) }
        item {
            Panel {
                FieldBlock(
                    title = stringResource(R.string.settings_double_tap),
                    subtitle = stringResource(R.string.settings_double_tap_hint),
                ) {
                    GuopianSegmentedRail(
                        options = listOf(
                            DoubleTapAction.KEEP to stringResource(R.string.mark_keep),
                            DoubleTapAction.PENDING to stringResource(R.string.mark_pending),
                            DoubleTapAction.TO_DELETE to stringResource(R.string.mark_to_delete),
                            DoubleTapAction.TOGGLE_PLAY to stringResource(R.string.settings_double_tap_play),
                        ),
                        selected = ui.doubleTapAction,
                        onSelect = viewModel::setDoubleTapAction,
                    )
                }
                GuopianHairline()
                SwitchRow(
                    title = stringResource(R.string.settings_swap_swipe),
                    subtitle = stringResource(R.string.settings_swap_swipe_hint),
                    checked = ui.swapSwipe,
                    onCheckedChange = viewModel::setSwapSwipe,
                )
                GuopianHairline()
                SwitchRow(
                    title = stringResource(R.string.settings_mark_auto_next),
                    subtitle = stringResource(R.string.settings_mark_auto_next_hint),
                    checked = ui.markAutoNext,
                    onCheckedChange = viewModel::setMarkAutoNext,
                )
            }
        }

        item { SectionLabel(stringResource(R.string.settings_section_about)) }
        item {
            Panel {
                ValueRow(
                    title = stringResource(R.string.app_name),
                    value = "v${BuildConfig.VERSION_NAME}",
                )
                GuopianHairline()
                Text(
                    text = stringResource(R.string.privacy_local_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuopianColors.InkMute,
                    modifier = Modifier.padding(horizontal = GuopianDimens.Page, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = GuopianColors.InkMute,
        modifier = Modifier.padding(
            start = GuopianDimens.Page,
            end = GuopianDimens.Page,
            top = GuopianDimens.Module,
            bottom = GuopianDimens.Grid,
        ),
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GuopianDimens.Page)
            .guopianCard(),
        content = { content() },
    )
}

@Composable
private fun FieldBlock(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = GuopianDimens.Page, vertical = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = GuopianColors.Ink,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = GuopianColors.InkMute,
        )
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    chevron: Boolean = true,
    actionHint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GuopianDimens.Page, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = GuopianColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.InkMute,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            actionHint != null -> Text(
                text = actionHint,
                style = MaterialTheme.typography.labelMedium,
                color = GuopianColors.Signal,
                fontWeight = FontWeight.SemiBold,
            )
            chevron -> Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = GuopianColors.InkMute,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GuopianDimens.Page, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = GuopianColors.Ink,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = GuopianColors.InkMute,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GuopianDimens.Page, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = GuopianDimens.ListGap)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = GuopianColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.InkMute,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GuopianColors.Signal,
                checkedBorderColor = GuopianColors.Signal,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = GuopianColors.Rule,
                uncheckedBorderColor = GuopianColors.Rule,
            ),
        )
    }
}
