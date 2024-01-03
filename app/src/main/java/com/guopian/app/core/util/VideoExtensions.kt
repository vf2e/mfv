package com.guopian.app.core.util

object VideoExtensions {
    val WHITELIST = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv",
        "ts", "m2ts", "mts", "3gp", "mpg", "mpeg", "rmvb", "rm", "vob", "asf",
        "f4v", "ogv", "divx", "xvid", "dat", "mpv", "mpe", "3g2",
    )

    /**
     * ExoPlayer/MediaCodec 对这些容器极易原生崩溃（不只是 PlaybackException），
     * 播放时应直接走 VLC，避免先进 Exo。
     */
    val PREFER_VLC = setOf(
        "avi", "wmv", "flv", "rmvb", "rm", "vob", "asf",
        "mpg", "mpeg", "mpe", "dat", "divx", "xvid", "ts", "m2ts", "mts",
    )

    fun isVideoFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in WHITELIST
    }

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    fun preferVlc(extOrFileName: String): Boolean {
        val ext = extOrFileName
            .substringAfterLast('.', missingDelimiterValue = extOrFileName)
            .lowercase()
        return ext in PREFER_VLC
    }
}
