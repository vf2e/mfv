package com.guopian.app.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.guopian.app.core.util.StorageAccessHelper
import com.guopian.app.core.util.VideoExtensions
import com.guopian.app.data.db.RootFolderDao
import com.guopian.app.data.db.VideoDao
import com.guopian.app.data.db.VideoEntity
import com.guopian.app.data.repo.RootBrowseStateStore
import com.guopian.app.domain.model.MarkStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val rootFolderDao: RootFolderDao,
    private val videoDao: VideoDao,
    private val browseStateStore: RootBrowseStateStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val root = rootFolderDao.getCurrent() ?: return Result.failure()
        val treeUri = Uri.parse(root.treeUri)
        val found = mutableListOf<VideoEntity>()
        val now = System.currentTimeMillis()

        try {
            // 重扫前先记下收藏/待定，避免 delete+insert 丢标记
            val dbMarks = videoDao.listMarkedPaths(root.id)
                .associate { it.relativePath to (it.markStatus to it.star) }
            val fileState = browseStateStore.read(treeUri)
            val mergedMarks = LinkedHashMap<String, Pair<String, Int>>()
            fileState.marks.forEach { (path, status) ->
                mergedMarks[path] = status.name to if (status == MarkStatus.KEEP) 1 else 0
            }
            // DB 优先（内存态更新于文件）
            dbMarks.forEach { (path, mark) -> mergedMarks[path] = mark }

            if (StorageAccessHelper.isFileRootUri(treeUri)) {
                val path = treeUri.path
                val dir = if (path.isNullOrBlank()) null else java.io.File(path)
                if (dir == null || !dir.isDirectory || !dir.canRead()) {
                    Timber.e("Cannot read file root path=%s", path)
                    return Result.failure(
                        workDataOf(KEY_ERROR to "无法读取本地目录，请确认已开启全部文件访问"),
                    )
                }
                traverseLocalFile(
                    folder = dir,
                    parentRelative = "",
                    depth = 0,
                    rootId = root.id,
                    now = now,
                    out = found,
                )
            } else {
                val treeRoot = DocumentFile.fromTreeUri(applicationContext, treeUri)
                if (treeRoot == null || !treeRoot.canRead()) {
                    Timber.e("Cannot read tree uri=%s canRead=%s", treeUri, treeRoot?.canRead())
                    return Result.failure(
                        workDataOf(KEY_ERROR to "无法读取所选文件夹，请重新授权"),
                    )
                }

                traverseDocumentFile(
                    treeUri = treeUri,
                    folder = treeRoot,
                    parentRelative = "",
                    depth = 0,
                    rootId = root.id,
                    now = now,
                    out = found,
                )

                // Cursor fallback if DocumentFile returned nothing (some OEM quirks)
                if (found.isEmpty()) {
                    Timber.w("DocumentFile found 0, trying DocumentsContract cursor scan")
                    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                    traverseWithDocId(
                        treeUri = treeUri,
                        parentDocId = rootDocId,
                        parentRelative = "",
                        depth = 0,
                        rootId = root.id,
                        now = now,
                        out = found,
                    )
                }
            }

            videoDao.deleteByRoot(root.id)
            val unique = found.distinctBy { it.documentId ?: it.docUri }
            if (unique.size != found.size) {
                Timber.w("Scan deduped %d → %d entries", found.size, unique.size)
            }
            // 写入时带上已保存的收藏状态；扫描阶段不做 MediaMetadata 探测（量大易 OOM/闪退）
            val withMarks = unique.map { entity ->
                val mark = mergedMarks[entity.relativePath]
                if (mark == null) {
                    entity
                } else {
                    entity.copy(markStatus = mark.first, star = mark.second)
                }
            }
            withMarks.chunked(80).forEach { chunk ->
                videoDao.upsertAll(chunk)
                if (isStopped) return Result.failure()
            }
            val stored = videoDao.countVisibleByRoot(root.id)

            // 把当前收藏写回目录 txt，方便下次/换机恢复
            val keepMarks = withMarks
                .filter { it.markStatus == MarkStatus.KEEP.name || it.markStatus == MarkStatus.PENDING.name }
                .associate {
                    it.relativePath to runCatching { MarkStatus.valueOf(it.markStatus) }
                        .getOrDefault(MarkStatus.TO_DELETE)
                }
            browseStateStore.write(
                treeUri,
                RootBrowseStateStore.BrowseState(
                    lastRelativePath = fileState.lastRelativePath,
                    marks = keepMarks,
                ),
            )

            rootFolderDao.updateScanStats(
                id = root.id,
                lastScannedAt = now,
                videoCount = stored,
            )
            Timber.i(
                "Scan finished: found=%d unique=%d stored=%d marks=%d from %s",
                found.size,
                unique.size,
                stored,
                keepMarks.size,
                treeUri,
            )
            val folderHint = when {
                StorageAccessHelper.isFileRootUri(treeUri) ->
                    treeUri.path?.let { StorageAccessHelper.displayPathForFile(java.io.File(it)) }.orEmpty()
                else -> try {
                    DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':', "")
                        .replace(':', '/')
                } catch (_: Throwable) {
                    ""
                }
            }
            return Result.success(
                workDataOf(
                    KEY_VIDEO_COUNT to stored,
                    KEY_FOLDER to folderHint,
                ),
            )
        } catch (e: Exception) {
            Timber.e(e, "Scan failed")
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "scan_failed")))
        }
    }

    private fun traverseLocalFile(
        folder: java.io.File,
        parentRelative: String,
        depth: Int,
        rootId: Long,
        now: Long,
        out: MutableList<VideoEntity>,
    ) {
        if (isStopped || depth > MAX_DEPTH) return
        val children = try {
            folder.listFiles()
        } catch (t: Throwable) {
            Timber.w(t, "listFiles failed at %s", folder.absolutePath)
            null
        } ?: return
        for (child in children) {
            if (isStopped) return
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (name.equals(RootBrowseStateStore.FILE_NAME, ignoreCase = true)) continue
            val childRelative = if (parentRelative.isEmpty()) name else "$parentRelative/$name"
            when {
                child.isDirectory -> {
                    traverseLocalFile(
                        folder = child,
                        parentRelative = childRelative,
                        depth = depth + 1,
                        rootId = rootId,
                        now = now,
                        out = out,
                    )
                }
                VideoExtensions.isVideoFileName(name) -> {
                    val uri = Uri.fromFile(child)
                    out += VideoEntity(
                        rootId = rootId,
                        docUri = uri.toString(),
                        documentId = child.absolutePath,
                        relativePath = childRelative,
                        parentSubPath = parentRelative,
                        depth = depth,
                        fileName = name,
                        ext = VideoExtensions.extensionOf(name),
                        mime = null,
                        sizeBytes = child.length(),
                        dateModified = child.lastModified(),
                        lastScannedAt = now,
                    )
                    setProgressAsync(workDataOf(KEY_FOUND to out.size))
                }
            }
        }
    }

    private fun traverseDocumentFile(
        treeUri: Uri,
        folder: DocumentFile,
        parentRelative: String,
        depth: Int,
        rootId: Long,
        now: Long,
        out: MutableList<VideoEntity>,
    ) {
        if (isStopped || depth > MAX_DEPTH) return
        val children = try {
            folder.listFiles()
        } catch (t: Throwable) {
            Timber.w(t, "listFiles failed at %s", folder.uri)
            emptyArray()
        }
        for (child in children) {
            if (isStopped) return
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (name.equals(RootBrowseStateStore.FILE_NAME, ignoreCase = true)) continue
            val childRelative = if (parentRelative.isEmpty()) name else "$parentRelative/$name"
            // Do NOT use DocumentFile.isFile — when MIME is null, isFile() is false and videos are skipped.
            val isDir = child.isDirectory
            when {
                isDir -> {
                    traverseDocumentFile(
                        treeUri = treeUri,
                        folder = child,
                        parentRelative = childRelative,
                        depth = depth + 1,
                        rootId = rootId,
                        now = now,
                        out = out,
                    )
                }
                isVideo(name, child.type) -> {
                    out += VideoEntity(
                        rootId = rootId,
                        docUri = child.uri.toString(),
                        documentId = runCatching {
                            DocumentsContract.getDocumentId(child.uri)
                        }.getOrNull(),
                        relativePath = childRelative,
                        parentSubPath = parentRelative,
                        depth = depth,
                        fileName = name,
                        ext = VideoExtensions.extensionOf(name),
                        mime = child.type,
                        sizeBytes = child.length(),
                        dateModified = child.lastModified(),
                        lastScannedAt = now,
                    )
                    setProgressAsync(workDataOf(KEY_FOUND to out.size))
                }
            }
        }
    }

    private fun traverseWithDocId(
        treeUri: Uri,
        parentDocId: String,
        parentRelative: String,
        depth: Int,
        rootId: Long,
        now: Long,
        out: MutableList<VideoEntity>,
    ) {
        if (isStopped || depth > MAX_DEPTH) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            applicationContext.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (t: Throwable) {
            Timber.w(t, "query children failed docId=%s", parentDocId)
            null
        }
        if (cursor == null) {
            Timber.w("null cursor for children of %s", parentDocId)
            return
        }
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIdx < 0 || nameIdx < 0) return

            while (c.moveToNext()) {
                if (isStopped) return
                val docId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                if (name.startsWith(".")) continue
                if (name.equals(RootBrowseStateStore.FILE_NAME, ignoreCase = true)) continue
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val modified = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                val childRelative = if (parentRelative.isEmpty()) name else "$parentRelative/$name"
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                    mime == "vnd.android.document/directory"

                if (isDir) {
                    traverseWithDocId(treeUri, docId, childRelative, depth + 1, rootId, now, out)
                } else if (isVideo(name, mime)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    out += VideoEntity(
                        rootId = rootId,
                        docUri = docUri.toString(),
                        documentId = docId,
                        relativePath = childRelative,
                        parentSubPath = parentRelative,
                        depth = depth,
                        fileName = name,
                        ext = VideoExtensions.extensionOf(name),
                        mime = mime,
                        sizeBytes = size,
                        dateModified = modified,
                        lastScannedAt = now,
                    )
                    setProgressAsync(workDataOf(KEY_FOUND to out.size))
                }
            }
        }
    }

    private fun isVideo(name: String, mime: String?): Boolean {
        if (mime?.startsWith("video/") == true) return true
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return false
        return VideoExtensions.isVideoFileName(name)
    }

    companion object {
        const val UNIQUE_WORK = "guopian_scan"
        const val KEY_VIDEO_COUNT = "video_count"
        const val KEY_FOUND = "found"
        const val KEY_ERROR = "error"
        const val KEY_FOLDER = "folder"
        private const val MAX_DEPTH = 32
    }
}
