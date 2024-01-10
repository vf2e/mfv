package com.guopian.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.guopian.app.R
import com.guopian.app.core.util.FileSizeFormatter
import com.guopian.app.core.util.PathAbbreviator
import com.guopian.app.core.util.StorageAccessHelper
import com.guopian.app.data.db.VideoEntity
import com.guopian.app.domain.model.MarkStatus
import com.guopian.app.domain.model.SortKey
import com.guopian.app.ui.cut.CutTargetSheet
import com.guopian.app.ui.theme.GuopianButtonShape
import com.guopian.app.ui.theme.GuopianCardShape
import com.guopian.app.ui.theme.GuopianColors
import com.guopian.app.ui.theme.GuopianDimens
import com.guopian.app.ui.theme.guopianCard
import com.guopian.app.ui.util.OpenDocumentTreeContract
import com.guopian.app.ui.util.launchDocumentTreePreferAndroid
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenSwipe: (startIndex: Int) -> Unit,
    showTagsFeatures: Boolean = false,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val videos = viewModel.videos.collectAsLazyPagingItems()
    val presets by viewModel.folderPresets.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    var showFileFolderPicker by remember { mutableStateOf(false) }
    val openTree = rememberLauncherForActivityResult(
        contract = OpenDocumentTreeContract(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onRootSelected(uri)
        }
    }

    val pickCutTarget = rememberLauncherForActivityResult(
        contract = OpenDocumentTreeContract(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.enqueueCut(uri)
        }
    }

    fun pickRootFolder() {
        if (StorageAccessHelper.hasAllFilesAccess()) {
            showFileFolderPicker = true
        } else {
            openTree.launchDocumentTreePreferAndroid()
        }
    }

    if (showFileFolderPicker) {
        FileFolderPickerDialog(
            onDismiss = { showFileFolderPicker = false },
            onConfirm = { path ->
                showFileFolderPicker = false
                viewModel.onRootFilePathSelected(path)
            },
        )
    }

    LaunchedEffect(uiState.cutMessage) {
        val msg = uiState.cutMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearCutMessage()
    }

    LaunchedEffect(uiState.scanResultMessage) {
        val msg = uiState.scanResultMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearScanResultMessage()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshLastCursor()
        }
    }

    if (uiState.showCutSheet) {
        CutTargetSheet(
            count = uiState.selectedIds.size,
            presets = presets,
            policy = uiState.conflictPolicy,
            onPolicyChange = viewModel::setConflictPolicy,
            onPickFolder = { pickCutTarget.launchDocumentTreePreferAndroid() },
            onPresetClick = viewModel::enqueueCutPreset,
            onDismiss = viewModel::dismissCutSheet,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = GuopianColors.Canvas,
        topBar = {
            LibraryBrandHeader(
                rootPath = uiState.rootPath,
                hasRoot = uiState.hasRoot,
                showSearch = showSearch,
                onToggleSearch = { showSearch = !showSearch },
                onScan = viewModel::scanNow,
                onPickRoot = { pickRootFolder() },
            )
        },
        bottomBar = {
            if (uiState.selectionMode) {
                SelectionBar(
                    count = uiState.selectedIds.size,
                    canTag = showTagsFeatures && tags.isNotEmpty(),
                    onCut = viewModel::openCutSheet,
                    onKeep = { viewModel.markSelected(MarkStatus.KEEP) },
                    onDelete = { viewModel.markSelected(MarkStatus.TO_DELETE) },
                    onTag = { showTagPicker = true },
                    onClear = viewModel::clearSelection,
                )
            }
        },
    ) { padding ->
        if (showTagPicker) {
            TagPickerDialog(
                tags = tags,
                onDismiss = { showTagPicker = false },
                onPick = { tagId ->
                    viewModel.tagSelected(tagId)
                    showTagPicker = false
                },
            )
        }
        if (!uiState.hasRoot) {
            EmptyRootState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onSelect = { pickRootFolder() },
                onGrantAllFiles = {
                    runCatching {
                        context.startActivity(StorageAccessHelper.manageAllFilesIntent(context))
                    }
                },
                showGrantAllFiles = !StorageAccessHelper.hasAllFilesAccess(),
            )
        } else {
            LibraryContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                uiState = uiState,
                videos = videos,
                showSearch = showSearch,
                onQuickFilter = viewModel::setQuickFilter,
                onClearExternalFilter = viewModel::clearExternalFilter,
                onPathKeyword = viewModel::setPathKeyword,
                onQueryText = viewModel::setQueryText,
                onScan = viewModel::scanNow,
                onToggleSelect = viewModel::toggleSelect,
                onEnterSelection = viewModel::enterSelection,
                onStartBrowse = {
                    scope.launch {
                        val start = viewModel.startBrowse() ?: return@launch
                        onOpenSwipe(start)
                    }
                },
                onResumeLast = {
                    scope.launch {
                        val start = viewModel.resumeLastBrowse()
                        if (start == null) {
                            snackbarHostState.showSnackbar("还没有上次浏览记录")
                            return@launch
                        }
                        onOpenSwipe(start)
                    }
                },
                onSetSort = viewModel::setSort,
                onCutKept = viewModel::prepareCutKept,
                onOpenItem = { videoId ->
                    scope.launch {
                        val start = viewModel.openSwipe(videoId) ?: return@launch
                        onOpenSwipe(start)
                    }
                },
            )
        }
    }
}

@Composable
private fun LibraryBrandHeader(
    rootPath: String?,
    hasRoot: Boolean,
    showSearch: Boolean,
    onToggleSearch: () -> Unit,
    onScan: () -> Unit,
    onPickRoot: () -> Unit,
) {
    // 单行顶栏：品牌 + 路径同行，右侧紧凑图标，压低标题区高度
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GuopianColors.Panel),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = GuopianDimens.Page, end = 4.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = GuopianColors.Slate,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = "·",
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.Rule,
            )
            Text(
                text = rootPath ?: stringResource(R.string.root_path_unset),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.InkMute,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HeaderIconButton(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.action_search),
                tint = if (showSearch) GuopianColors.Signal else GuopianColors.Ink,
                onClick = onToggleSearch,
            )
            if (hasRoot) {
                HeaderIconButton(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.action_scan),
                    tint = GuopianColors.Ink,
                    onClick = onScan,
                )
            }
            HeaderIconButton(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = stringResource(R.string.action_select_root),
                tint = GuopianColors.Ink,
                onClick = onPickRoot,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuopianDimens.Hairline)
                .background(GuopianColors.Rule),
        )
    }
}

@Composable
private fun HeaderIconButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(GuopianButtonShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    canTag: Boolean,
    onCut: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onTag: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GuopianColors.Panel),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuopianDimens.Hairline)
                .background(GuopianColors.Rule),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GuopianDimens.ListGap, vertical = GuopianDimens.Grid),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.selected_count, count),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.Ink,
                modifier = Modifier.padding(start = GuopianDimens.Grid),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.action_cut),
                modifier = Modifier
                    .clickable(onClick = onCut)
                    .padding(horizontal = GuopianDimens.ListGap, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.Signal,
                fontWeight = FontWeight.SemiBold,
            )
            if (canTag) {
                Text(
                    text = stringResource(R.string.action_tag),
                    modifier = Modifier
                        .clickable(onClick = onTag)
                        .padding(horizontal = GuopianDimens.ListGap, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = GuopianColors.Ink,
                )
            }
            Text(
                text = stringResource(R.string.mark_keep),
                modifier = Modifier
                    .clickable(onClick = onKeep)
                    .padding(horizontal = GuopianDimens.ListGap, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.Ink,
            )
            Text(
                text = stringResource(R.string.mark_to_delete),
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = GuopianDimens.ListGap, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.Ink,
            )
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = GuopianColors.InkMute,
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(
    modifier: Modifier,
    uiState: LibraryUiState,
    videos: androidx.paging.compose.LazyPagingItems<VideoEntity>,
    showSearch: Boolean,
    onQuickFilter: (QuickFilter) -> Unit,
    onClearExternalFilter: () -> Unit,
    onPathKeyword: (String) -> Unit,
    onQueryText: (String) -> Unit,
    onScan: () -> Unit,
    onToggleSelect: (Long) -> Unit,
    onEnterSelection: (Long) -> Unit,
    onStartBrowse: () -> Unit,
    onResumeLast: () -> Unit,
    onSetSort: (SortKey) -> Unit,
    onCutKept: () -> Unit,
    onOpenItem: (Long) -> Unit,
) {
    Column(modifier = modifier) {
        QuickFilterRow(current = uiState.quickFilter, onSelect = onQuickFilter)

        uiState.externalFilterLabel?.let { label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GuopianDimens.Page, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_filter_chip, label),
                    modifier = Modifier
                        .clip(GuopianButtonShape)
                        .background(GuopianColors.SignalSoft)
                        .padding(horizontal = GuopianDimens.ListGap, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = GuopianColors.Signal,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.library_filter_clear),
                    modifier = Modifier
                        .clickable(onClick = onClearExternalFilter)
                        .padding(GuopianDimens.Grid),
                    style = MaterialTheme.typography.labelMedium,
                    color = GuopianColors.InkMute,
                )
            }
        }

        if (uiState.videoCount > 0 || videos.itemCount > 0) {
            BrowseActionRow(
                hasLast = !uiState.lastRelativePath.isNullOrBlank(),
                onStartBrowse = onStartBrowse,
                onResumeLast = onResumeLast,
                onCutKept = onCutKept,
            )
        }

        SortAndCountRow(
            sort = uiState.sort,
            videoCount = uiState.videoCount,
            pageSize = uiState.pageSize,
            onSelect = onSetSort,
        )

        if (showSearch) {
            SearchField(
                value = uiState.pathKeyword,
                onValueChange = onPathKeyword,
                placeholder = stringResource(R.string.filter_path_keyword),
            )
            SearchField(
                value = uiState.queryText,
                onValueChange = onQueryText,
                placeholder = stringResource(R.string.filter_search),
            )
        }

        if (uiState.isScanning) {
            Text(
                text = stringResource(R.string.scanning_progress, uiState.scanFound),
                modifier = Modifier.padding(horizontal = GuopianDimens.Page, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.Signal,
            )
        }

        uiState.scanError?.let { err ->
            Text(
                text = stringResource(R.string.scan_failed, err),
                modifier = Modifier.padding(horizontal = GuopianDimens.Page, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.Danger,
            )
        }

        val showLoading = uiState.isScanning && videos.itemCount == 0
        val showEmpty = !showLoading &&
            videos.itemCount == 0 &&
            videos.loadState.refresh is LoadState.NotLoading

        when {
            showLoading -> {
                LoadingPane(modifier = Modifier.weight(1f))
            }
            showEmpty -> {
                EmptyVideosState(
                    modifier = Modifier.weight(1f),
                    onScan = onScan,
                    hint = if (uiState.hasRoot) {
                        stringResource(R.string.scan_zero_hint)
                    } else {
                        stringResource(R.string.library_empty_subtitle)
                    },
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(148.dp),
                    contentPadding = PaddingValues(
                        horizontal = GuopianDimens.Page,
                        vertical = GuopianDimens.Grid,
                    ),
                    verticalArrangement = Arrangement.spacedBy(GuopianDimens.ListGap),
                    horizontalArrangement = Arrangement.spacedBy(GuopianDimens.ListGap),
                    modifier = Modifier.weight(1f),
                ) {
                    items(
                        count = videos.itemCount,
                        key = { index -> videos.peek(index)?.id ?: index },
                    ) { index ->
                        val item = videos[index]
                        if (item != null) {
                            VideoGridItem(
                                video = item,
                                selected = item.id in uiState.selectedIds,
                                selectionMode = uiState.selectionMode,
                                onClick = {
                                    if (uiState.selectionMode) {
                                        onToggleSelect(item.id)
                                    } else {
                                        onOpenItem(item.id)
                                    }
                                },
                                onLongClick = { onEnterSelection(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortAndCountRow(
    sort: com.guopian.app.domain.model.SortSpec,
    videoCount: Int,
    pageSize: Int,
    onSelect: (SortKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = GuopianDimens.Page, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(GuopianDimens.Grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.video_count_paged, videoCount, pageSize),
            style = MaterialTheme.typography.labelMedium,
            color = GuopianColors.InkMute,
            maxLines = 1,
        )
        Text(
            text = "·",
            style = MaterialTheme.typography.labelMedium,
            color = GuopianColors.Rule,
        )
        val options = listOf(
            SortKey.DATE_MODIFIED to R.string.sort_date,
            SortKey.NAME to R.string.sort_name,
            SortKey.SIZE to R.string.sort_size,
            SortKey.DURATION to R.string.sort_duration,
        )
        options.forEach { (key, label) ->
            val selected = sort.key == key
            val arrow = when {
                !selected -> ""
                sort.ascending -> " ↑"
                else -> " ↓"
            }
            Text(
                text = stringResource(label) + arrow,
                modifier = Modifier
                    .clip(GuopianButtonShape)
                    .background(if (selected) GuopianColors.SignalSoft else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) GuopianColors.Signal else GuopianColors.InkMute,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BrowseActionRow(
    hasLast: Boolean,
    onStartBrowse: () -> Unit,
    onResumeLast: () -> Unit,
    onCutKept: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = GuopianDimens.Page, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(GuopianDimens.Grid),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.action_start_browse),
            modifier = Modifier
                .clip(GuopianButtonShape)
                .background(GuopianColors.Signal)
                .clickable(onClick = onStartBrowse)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = GuopianColors.OnSignal,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (hasLast) {
            Text(
                text = stringResource(R.string.action_resume_last),
                modifier = Modifier
                    .clip(GuopianButtonShape)
                    .border(GuopianDimens.Hairline, GuopianColors.Signal, GuopianButtonShape)
                    .clickable(onClick = onResumeLast)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.Signal,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Text(
            text = stringResource(R.string.action_cut_kept_short),
            modifier = Modifier
                .clip(GuopianButtonShape)
                .border(GuopianDimens.Hairline, GuopianColors.Rule, GuopianButtonShape)
                .clickable(onClick = onCutKept)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = GuopianColors.Ink,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GuopianDimens.Page, vertical = GuopianDimens.Grid)
            .border(GuopianDimens.Hairline, GuopianColors.Rule, GuopianButtonShape)
            .background(GuopianColors.Panel, GuopianButtonShape)
            .padding(horizontal = 14.dp, vertical = GuopianDimens.ListGap),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = GuopianColors.InkMute,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = GuopianColors.Ink),
            cursorBrush = SolidColor(GuopianColors.Signal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LoadingPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = GuopianColors.Signal,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun QuickFilterRow(
    current: QuickFilter,
    onSelect: (QuickFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GuopianColors.Panel),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GuopianDimens.Page, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(GuopianDimens.Grid),
        ) {
            val filters = listOf(
                QuickFilter.ALL to R.string.filter_all,
                QuickFilter.KEEP to R.string.mark_keep,
                QuickFilter.PENDING to R.string.mark_pending,
                QuickFilter.TO_DELETE to R.string.mark_to_delete,
                QuickFilter.MOVED to R.string.mark_moved,
            )
            filters.forEach { (filter, label) ->
                val selected = current == filter
                Text(
                    text = stringResource(label),
                    modifier = Modifier
                        .clip(GuopianButtonShape)
                        .background(if (selected) GuopianColors.SignalSoft else Color.Transparent)
                        .clickable { onSelect(filter) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) GuopianColors.Signal else GuopianColors.InkMute,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuopianDimens.Hairline)
                .background(GuopianColors.Rule),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGridItem(
    video: VideoEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val parentDir = PathAbbreviator.parentDirectory(video.relativePath, video.fileName)
    val sizeLabel = FileSizeFormatter.format(video.sizeBytes)
    val extLabel = video.ext.uppercase().ifEmpty { "VIDEO" }
    var hasThumb by remember(video.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .guopianCard(elevation = if (selected) 4.dp else GuopianDimens.CardElevation)
            .then(
                if (selected) {
                    Modifier.border(2.dp, GuopianColors.Signal, GuopianCardShape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1F2937),
                            Color(0xFF111827),
                            Color(0xFF0B1220),
                        ),
                    ),
                ),
        ) {
            if (!hasThumb) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = 18.dp.toPx()
                    val line = Color.White.copy(alpha = 0.045f)
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(
                            color = line,
                            start = Offset(x, 0f),
                            end = Offset(x + size.height, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                        x += step
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(GuopianButtonShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = extLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.88f),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            VideoThumbnailImage(
                videoId = video.id,
                docUri = video.docUri,
                contentDescription = video.fileName,
                onLoaded = { hasThumb = it },
            )
            // 底部暗角，让大小角标更清晰
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        ),
                    ),
            )
            Text(
                text = sizeLabel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(GuopianDimens.Grid)
                    .clip(GuopianButtonShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            StatusBadge(
                status = video.markStatus,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(GuopianDimens.Grid),
            )
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(GuopianDimens.Grid)
                        .size(18.dp)
                        .clip(GuopianButtonShape)
                        .border(
                            1.5.dp,
                            if (selected) GuopianColors.Signal else Color.White.copy(alpha = 0.7f),
                            GuopianButtonShape,
                        )
                        .background(if (selected) GuopianColors.Signal else Color.Transparent),
                )
            }
        }
        Column(modifier = Modifier.padding(GuopianDimens.ListGap)) {
            Text(
                text = video.fileName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = GuopianColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
            if (parentDir != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = parentDir,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = GuopianColors.InkMute,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    if (status == MarkStatus.KEEP.name) {
        Box(
            modifier = modifier
                .size(32.dp)
                .clip(GuopianButtonShape)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = stringResource(R.string.mark_keep),
                tint = Color(0xFFFF2D55),
                modifier = Modifier.size(18.dp),
            )
        }
        return
    }
    val (labelRes, bg, fg) = when (status) {
        MarkStatus.PENDING.name -> Triple(R.string.mark_pending, GuopianColors.SignalSoft, GuopianColors.Slate)
        MarkStatus.TO_DELETE.name, MarkStatus.UNSORTED.name ->
            Triple(R.string.mark_to_delete, GuopianColors.Danger, Color.White)
        MarkStatus.MOVED.name -> Triple(R.string.mark_moved, GuopianColors.Slate, Color.White)
        else -> return
    }
    Text(
        text = stringResource(labelRes),
        modifier = modifier
            .clip(GuopianButtonShape)
            .background(bg)
            .padding(horizontal = GuopianDimens.Grid, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}

@Composable
private fun EmptyRootState(
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onGrantAllFiles: () -> Unit,
    showGrantAllFiles: Boolean,
) {
    Column(
        modifier = modifier.padding(GuopianDimens.Module),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge,
            color = GuopianColors.Slate,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(GuopianDimens.Grid))
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = GuopianColors.InkMute,
        )
        Spacer(modifier = Modifier.height(GuopianDimens.Module))
        Text(
            text = stringResource(R.string.library_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = GuopianColors.Ink,
        )
        if (showGrantAllFiles) {
            Spacer(modifier = Modifier.height(GuopianDimens.ListGap))
            Text(
                text = stringResource(R.string.all_files_access_hint),
                style = MaterialTheme.typography.bodySmall,
                color = GuopianColors.InkMute,
            )
        }
        Spacer(modifier = Modifier.height(GuopianDimens.Module))
        Text(
            text = stringResource(R.string.action_select_root),
            modifier = Modifier
                .clip(GuopianButtonShape)
                .background(GuopianColors.Signal)
                .clickable(onClick = onSelect)
                .padding(horizontal = GuopianDimens.Module, vertical = 14.dp),
            style = MaterialTheme.typography.titleMedium,
            color = GuopianColors.OnSignal,
            fontWeight = FontWeight.SemiBold,
        )
        if (showGrantAllFiles) {
            Spacer(modifier = Modifier.height(GuopianDimens.ListGap))
            Text(
                text = stringResource(R.string.action_grant_all_files),
                modifier = Modifier
                    .clip(GuopianButtonShape)
                    .background(GuopianColors.SignalSoft)
                    .clickable(onClick = onGrantAllFiles)
                    .padding(horizontal = GuopianDimens.Module, vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.Signal,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyVideosState(
    modifier: Modifier = Modifier,
    onScan: () -> Unit,
    hint: String,
) {
    Column(
        modifier = modifier.padding(horizontal = GuopianDimens.Module, vertical = GuopianDimens.Module * 2),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            color = GuopianColors.Slate,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(GuopianDimens.Grid))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyLarge,
            color = GuopianColors.InkMute,
        )
        Spacer(modifier = Modifier.height(GuopianDimens.Module))
        Text(
            text = stringResource(R.string.action_scan),
            modifier = Modifier
                .clip(GuopianButtonShape)
                .background(GuopianColors.SignalSoft)
                .clickable(onClick = onScan)
                .padding(horizontal = GuopianDimens.Page, vertical = GuopianDimens.ListGap),
            style = MaterialTheme.typography.labelLarge,
            color = GuopianColors.Signal,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TagPickerDialog(
    tags: List<com.guopian.app.data.db.TagEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .guopianCard()
                .padding(GuopianDimens.Page),
        ) {
            Text(
                text = stringResource(R.string.action_tag),
                style = MaterialTheme.typography.titleMedium,
                color = GuopianColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(GuopianDimens.ListGap))
            tags.forEach { tag ->
                val swatch = runCatching {
                    Color(android.graphics.Color.parseColor(tag.color))
                }.getOrDefault(GuopianColors.Signal)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(tag.id) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(GuopianButtonShape)
                            .background(swatch),
                    )
                    Spacer(modifier = Modifier.size(GuopianDimens.ListGap))
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = GuopianColors.Ink,
                    )
                }
            }
            Spacer(modifier = Modifier.height(GuopianDimens.Grid))
            Text(
                text = stringResource(R.string.action_cancel),
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onDismiss)
                    .padding(GuopianDimens.Grid),
                style = MaterialTheme.typography.labelLarge,
                color = GuopianColors.InkMute,
            )
        }
    }
}
