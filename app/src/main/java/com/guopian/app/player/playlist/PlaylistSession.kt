package com.guopian.app.player.playlist

import com.guopian.app.data.repo.VideoRepository
import com.guopian.app.domain.model.FilterSpec
import com.guopian.app.domain.model.SortSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistSession @Inject constructor(
    private val videoRepository: VideoRepository,
) {
    data class Snapshot(
        val sessionId: String,
        val rootId: Long,
        val filter: FilterSpec,
        val sort: SortSpec,
        val ids: LongArray,
        val randomSeed: Long,
        val pageIndex: Int,
        val pageSize: Int,
        val totalCount: Int,
    ) {
        val pageCount: Int
            get() = if (totalCount <= 0 || pageSize <= 0) 0 else (totalCount + pageSize - 1) / pageSize

        val hasPrevPage: Boolean get() = pageIndex > 0
        val hasNextPage: Boolean get() = pageIndex + 1 < pageCount
        val globalOffset: Int get() = pageIndex * pageSize
    }

    @Volatile
    private var snapshot: Snapshot? = null

    data class OpenResult(
        val snapshot: Snapshot,
        val startIndex: Int,
    )

    suspend fun open(
        rootId: Long,
        filter: FilterSpec,
        sort: SortSpec,
        startVideoId: Long,
        randomSeed: Long = System.currentTimeMillis(),
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): OpenResult {
        val size = pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val total = videoRepository.countFiltered(rootId, filter)
        if (total <= 0) {
            val empty = Snapshot(
                sessionId = UUID.randomUUID().toString(),
                rootId = rootId,
                filter = filter,
                sort = sort,
                ids = longArrayOf(),
                randomSeed = randomSeed,
                pageIndex = 0,
                pageSize = size,
                totalCount = 0,
            )
            snapshot = empty
            return OpenResult(empty, 0)
        }

        val located = locatePage(
            rootId = rootId,
            filter = filter,
            sort = sort,
            randomSeed = randomSeed,
            pageSize = size,
            totalCount = total,
            targetVideoId = startVideoId,
        )
        val snap = Snapshot(
            sessionId = UUID.randomUUID().toString(),
            rootId = rootId,
            filter = filter,
            sort = sort,
            ids = located.ids.toLongArray(),
            randomSeed = randomSeed,
            pageIndex = located.pageIndex,
            pageSize = size,
            totalCount = total,
        )
        snapshot = snap
        return OpenResult(snap, located.localIndex)
    }

    suspend fun openPage(pageIndex: Int): OpenResult? {
        val current = snapshot ?: return null
        val pageCount = current.pageCount
        if (pageCount <= 0) return null
        val target = pageIndex.coerceIn(0, pageCount - 1)
        val ids = videoRepository.queryIds(
            rootId = current.rootId,
            filter = current.filter,
            sort = current.sort,
            randomSeed = current.randomSeed,
            limit = current.pageSize,
            offset = target * current.pageSize,
        )
        val total = videoRepository.countFiltered(current.rootId, current.filter)
        val snap = current.copy(
            sessionId = UUID.randomUUID().toString(),
            ids = ids.toLongArray(),
            pageIndex = target,
            totalCount = total,
        )
        snapshot = snap
        return OpenResult(snap, 0)
    }

    fun current(): Snapshot? = snapshot

    fun idAt(index: Int): Long? {
        val ids = snapshot?.ids ?: return null
        if (index !in ids.indices) return null
        return ids[index]
    }

    fun size(): Int = snapshot?.ids?.size ?: 0

    fun close() {
        snapshot = null
    }

    private data class LocatedPage(
        val ids: List<Long>,
        val pageIndex: Int,
        val localIndex: Int,
    )

    private suspend fun locatePage(
        rootId: Long,
        filter: FilterSpec,
        sort: SortSpec,
        randomSeed: Long,
        pageSize: Int,
        totalCount: Int,
        targetVideoId: Long,
    ): LocatedPage {
        var offset = 0
        while (offset < totalCount) {
            val chunk = videoRepository.queryIds(
                rootId = rootId,
                filter = filter,
                sort = sort,
                randomSeed = randomSeed,
                limit = pageSize,
                offset = offset,
            )
            if (chunk.isEmpty()) break
            val local = chunk.indexOf(targetVideoId)
            if (local >= 0) {
                return LocatedPage(
                    ids = chunk,
                    pageIndex = offset / pageSize,
                    localIndex = local,
                )
            }
            if (chunk.size < pageSize) break
            offset += pageSize
        }
        val first = videoRepository.queryIds(
            rootId = rootId,
            filter = filter,
            sort = sort,
            randomSeed = randomSeed,
            limit = pageSize,
            offset = 0,
        )
        return LocatedPage(ids = first, pageIndex = 0, localIndex = 0)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val MIN_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 500
    }
}
