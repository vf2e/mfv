package com.guopian.app.data.repo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.guopian.app.core.util.StorageAccessHelper
import com.guopian.app.domain.model.MarkStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在总文件夹根目录写入 `guopian_browse.txt`：
 * - last=上次刷到的相对路径
 * - KEEP/PENDING 列表，重新扫描后可恢复收藏索引
 */
@Singleton
class RootBrowseStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class BrowseState(
        val lastRelativePath: String? = null,
        val marks: Map<String, MarkStatus> = emptyMap(),
    )

    fun read(treeUri: Uri): BrowseState {
        return runCatching {
            openInput(treeUri)?.use { input ->
                parse(BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText())
            } ?: BrowseState()
        }.onFailure {
            Timber.w(it, "read browse state failed")
        }.getOrDefault(BrowseState())
    }

    fun write(treeUri: Uri, state: BrowseState) {
        runCatching {
            openOutput(treeUri)?.use { output ->
                BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { w ->
                    w.write("# Guopian browse state — do not delete\n")
                    w.write("version=1\n")
                    val last = state.lastRelativePath?.trim().orEmpty()
                    if (last.isNotEmpty()) {
                        w.write("last=$last\n")
                    }
                    state.marks.entries
                        .sortedBy { it.key.lowercase() }
                        .forEach { (path, status) ->
                            if (status == MarkStatus.KEEP || status == MarkStatus.PENDING) {
                                w.write("${status.name}=$path\n")
                            }
                        }
                }
            }
        }.onFailure {
            Timber.w(it, "write browse state failed")
        }
    }

    fun updateLast(treeUri: Uri, relativePath: String) {
        val path = relativePath.trim()
        if (path.isEmpty()) return
        val current = read(treeUri)
        if (current.lastRelativePath == path) return
        write(treeUri, current.copy(lastRelativePath = path))
    }

    fun upsertMark(treeUri: Uri, relativePath: String, status: MarkStatus) {
        val path = relativePath.trim()
        if (path.isEmpty()) return
        val current = read(treeUri)
        val nextMarks = current.marks.toMutableMap()
        when (status) {
            MarkStatus.KEEP, MarkStatus.PENDING -> nextMarks[path] = status
            else -> nextMarks.remove(path)
        }
        write(treeUri, current.copy(marks = nextMarks))
    }

    fun replaceMarks(treeUri: Uri, marks: Map<String, MarkStatus>, keepLast: Boolean = true) {
        val current = read(treeUri)
        write(
            treeUri,
            BrowseState(
                lastRelativePath = if (keepLast) current.lastRelativePath else null,
                marks = marks.filterValues { it == MarkStatus.KEEP || it == MarkStatus.PENDING },
            ),
        )
    }

    private fun parse(text: String): BrowseState {
        var last: String? = null
        val marks = linkedMapOf<String, MarkStatus>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty()) return@forEach
            when (key) {
                "version" -> Unit
                "last" -> last = value
                MarkStatus.KEEP.name -> marks[value] = MarkStatus.KEEP
                MarkStatus.PENDING.name -> marks[value] = MarkStatus.PENDING
            }
        }
        return BrowseState(lastRelativePath = last, marks = marks)
    }

    private fun openInput(treeUri: Uri): java.io.InputStream? {
        if (StorageAccessHelper.isFileRootUri(treeUri)) {
            val file = File(treeUri.path ?: return null, FILE_NAME)
            if (!file.isFile) return null
            return file.inputStream()
        }
        val doc = findStateDoc(treeUri) ?: return null
        return context.contentResolver.openInputStream(doc.uri)
    }

    private fun openOutput(treeUri: Uri): java.io.OutputStream? {
        if (StorageAccessHelper.isFileRootUri(treeUri)) {
            val dir = File(treeUri.path ?: return null)
            if (!dir.isDirectory) return null
            val file = File(dir, FILE_NAME)
            return file.outputStream()
        }
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val existing = root.findFile(FILE_NAME)
        val target = existing
            ?: root.createFile("text/plain", FILE_NAME.removeSuffix(".txt"))
            ?: root.createFile("text/plain", FILE_NAME)
            ?: return null
        // "wt" truncates; some providers ignore mode — recreate if needed
        return context.contentResolver.openOutputStream(target.uri, "wt")
            ?: context.contentResolver.openOutputStream(target.uri)
    }

    private fun findStateDoc(treeUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return root.findFile(FILE_NAME)
            ?: root.listFiles().firstOrNull { it.name.equals(FILE_NAME, ignoreCase = true) }
    }

    companion object {
        const val FILE_NAME = "guopian_browse.txt"
    }
}
