package com.blaze.player.playback

/**
 * Deterministic, player-independent autoplay state machine.  PlaybackService
 * uses the same rules as this harness: preparation is an effect, and play is
 * emitted only once for the matching successful preparation.
 */
class AutoplayTransitionController {
    enum class Effect { PREPARE, PLAY }

    private var activeMediaId: String? = null
    private var preparePending = false
    private var playEmitted = false

    fun request(mediaId: String, context: AutoplayContext, retry: Boolean = false): List<Effect> {
        if (mediaId.isEmpty() || !AutoplayPolicy.allows(AutoplayRequest(context, mediaId))) return emptyList()
        if (!retry && activeMediaId == mediaId) return emptyList()
        activeMediaId = mediaId
        preparePending = true
        playEmitted = false
        return listOf(Effect.PREPARE)
    }

    fun prepared(mediaId: String): List<Effect> {
        if (mediaId != activeMediaId || !preparePending || playEmitted) return emptyList()
        preparePending = false
        playEmitted = true
        return listOf(Effect.PLAY)
    }

    fun failed(mediaId: String): List<Effect> {
        if (mediaId == activeMediaId) preparePending = false
        return emptyList()
    }

    /** Audio-focus denial invalidates the pending autoplay, without a stale retry. */
    fun focusDenied(mediaId: String): List<Effect> {
        if (mediaId == activeMediaId) preparePending = false
        return emptyList()
    }
}
