package com.blaze.player.playback

import androidx.media3.common.PlaybackException

/** Small, UI-safe state contract shared by the service and its controller surface. */
enum class PlaybackStatus { IDLE, LOADING, READY, ERROR }

data class PlaybackError(val title: String, val message: String, val canRetry: Boolean = true)

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val error: PlaybackError? = null,
    val mediaId: String? = null,
)

object PlaybackErrorMapper {
    fun map(error: PlaybackException): PlaybackError {
        val cause = error.cause?.message.orEmpty().lowercase()
        val text = when {
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                cause.contains("timeout") || cause.contains("network") ->
                PlaybackError("Network error", "Check your connection, then retry.")
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                cause.contains("http") || cause.contains("403") || cause.contains("404") ->
                PlaybackError("Video unavailable", "The server rejected or could not find this video.")
            cause.contains("tls") || cause.contains("ssl") || cause.contains("certificate") ->
                PlaybackError("Secure connection failed", "The video host could not be reached securely.")
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                PlaybackError("Unsupported video", "This video format is not supported.", canRetry = false)
            else -> PlaybackError("Playback failed", "The video could not be prepared. Try again or choose another video.")
        }
        return text
    }
}
