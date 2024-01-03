package com.guopian.app.data.db

data class StatusCountRow(
    val markStatus: String,
    val cnt: Int,
)

data class ExtCountRow(
    val ext: String,
    val cnt: Int,
)

data class FolderCountRow(
    val parentSubPath: String,
    val cnt: Int,
)

data class TagWithCount(
    val id: Long,
    val name: String,
    val color: String,
    val createdAt: Long,
    val videoCount: Int,
)

data class MarkedPathRow(
    val relativePath: String,
    val markStatus: String,
    val star: Int,
)
