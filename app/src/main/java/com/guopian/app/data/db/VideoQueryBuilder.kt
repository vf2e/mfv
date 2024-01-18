package com.guopian.app.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.guopian.app.domain.model.FilterSpec
import com.guopian.app.domain.model.MarkStatus
import com.guopian.app.domain.model.SortKey
import com.guopian.app.domain.model.SortSpec

object VideoQueryBuilder {

    fun buildPagingQuery(
        rootId: Long,
        filter: FilterSpec,
        sort: SortSpec,
        randomSeed: Long = 0L,
    ): SupportSQLiteQuery {
        val (where, args) = buildWhere(rootId, filter)
        val order = buildOrder(sort, randomSeed)
        return SimpleSQLiteQuery(
            "SELECT * FROM video WHERE $where $order",
            args.toTypedArray(),
        )
    }

    fun buildPageQuery(
        rootId: Long,
        filter: FilterSpec,
        sort: SortSpec,
        randomSeed: Long = 0L,
        limit: Int,
        offset: Int,
    ): SupportSQLiteQuery {
        val (where, args) = buildWhere(rootId, filter)
        val order = buildOrder(sort, randomSeed)
        args += limit.coerceAtLeast(1)
        args += offset.coerceAtLeast(0)
        return SimpleSQLiteQuery(
            "SELECT * FROM video WHERE $where $order LIMIT ? OFFSET ?",
            args.toTypedArray(),
        )
    }

    fun buildIdsQuery(
        rootId: Long,
        filter: FilterSpec,
        sort: SortSpec,
        randomSeed: Long = 0L,
        limit: Int? = null,
        offset: Int = 0,
    ): SupportSQLiteQuery {
        val (where, args) = buildWhere(rootId, filter)
        val order = buildOrder(sort, randomSeed)
        val lim = if (limit != null && limit > 0) {
            args += limit
            args += offset.coerceAtLeast(0)
            " LIMIT ? OFFSET ?"
        } else {
            ""
        }
        return SimpleSQLiteQuery(
            "SELECT id FROM video WHERE $where $order$lim",
            args.toTypedArray(),
        )
    }

    fun buildCountQuery(rootId: Long, filter: FilterSpec): SupportSQLiteQuery {
        val (where, args) = buildWhere(rootId, filter)
        return SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM video WHERE $where",
            args.toTypedArray(),
        )
    }

    private fun buildWhere(rootId: Long, filter: FilterSpec): Pair<String, MutableList<Any>> {
        val clauses = mutableListOf("root_id = ?", "is_missing = 0")
        val args = mutableListOf<Any>(rootId)

        if (filter.onlyUnmarked) {
            // 未收藏/未待定 = 待删除（含旧数据 UNSORTED）
            clauses += "mark_status IN (?, ?)"
            args += MarkStatus.TO_DELETE.name
            args += MarkStatus.UNSORTED.name
        } else if (filter.markStatuses.isNotEmpty()) {
            val placeholders = filter.markStatuses.joinToString(",") { "?" }
            clauses += "mark_status IN ($placeholders)"
            filter.markStatuses.forEach { args += it.name }
        }

        filter.pathContains.forEach { keyword ->
            val k = keyword.trim()
            if (k.isNotEmpty()) {
                clauses += "relative_path LIKE ?"
                args += "%$k%"
            }
        }

        filter.pathExcludes.forEach { keyword ->
            val k = keyword.trim()
            if (k.isNotEmpty()) {
                clauses += "relative_path NOT LIKE ?"
                args += "%$k%"
            }
        }

        if (filter.parentSubPaths.isNotEmpty()) {
            val orParts = filter.parentSubPaths.map { "parent_sub_path = ? OR parent_sub_path LIKE ?" }
            clauses += "(" + orParts.joinToString(" OR ") + ")"
            filter.parentSubPaths.forEach { path ->
                args += path
                args += "$path/%"
            }
        }

        if (filter.extensions.isNotEmpty()) {
            val placeholders = filter.extensions.joinToString(",") { "?" }
            clauses += "ext IN ($placeholders)"
            filter.extensions.forEach { args += it.lowercase() }
        }

        filter.tagId?.let { tagId ->
            clauses += "id IN (SELECT video_id FROM video_tag WHERE tag_id = ?)"
            args += tagId
        }

        val q = filter.queryText?.trim().orEmpty()
        if (q.isNotEmpty()) {
            clauses += "(file_name LIKE ? OR relative_path LIKE ? OR IFNULL(note,'') LIKE ?)"
            args += "%$q%"
            args += "%$q%"
            args += "%$q%"
        }

        return clauses.joinToString(" AND ") to args
    }

    private fun buildOrder(sort: SortSpec, randomSeed: Long): String {
        val dir = if (sort.ascending) "ASC" else "DESC"
        return when (sort.key) {
            SortKey.DATE_MODIFIED -> "ORDER BY date_modified $dir, id $dir"
            SortKey.NAME -> "ORDER BY file_name COLLATE NOCASE $dir, id $dir"
            SortKey.SIZE -> "ORDER BY size_bytes $dir, id $dir"
            SortKey.DURATION -> "ORDER BY duration_ms $dir, id $dir"
            SortKey.RESOLUTION -> "ORDER BY (width * height) $dir, id $dir"
            SortKey.PATH -> "ORDER BY relative_path COLLATE NOCASE $dir, id $dir"
            SortKey.RANDOM -> "ORDER BY (id * $randomSeed) % 2147483647"
        }
    }
}
