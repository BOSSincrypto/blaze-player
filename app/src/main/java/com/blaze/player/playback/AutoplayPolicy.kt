package com.blaze.player.playback

/** Entry points which are allowed to start playback after preparation. */
enum class AutoplayContext {
    PICKER,
    SHARE,
    HISTORY,
    PLAYLIST_START,
    PLAYLIST_NEXT,
    RECONNECT,
    COLD_LAUNCH,
    RETRY,
}

data class AutoplayRequest(val context: AutoplayContext, val mediaId: String)

/** Pure policy keeps lifecycle/reconnect events from accidentally becoming play commands. */
object AutoplayPolicy {
    fun allows(request: AutoplayRequest): Boolean = when (request.context) {
        AutoplayContext.PICKER,
        AutoplayContext.SHARE,
        AutoplayContext.HISTORY,
        AutoplayContext.PLAYLIST_START,
        AutoplayContext.PLAYLIST_NEXT,
        AutoplayContext.RETRY -> true
        AutoplayContext.RECONNECT,
        AutoplayContext.COLD_LAUNCH -> false
    }
}
