package com.blaze.player.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    companion object {
        val prepareAndAutoplay = SessionCommand("com.blaze.player.PREPARE_AUTOPLAY", Bundle.EMPTY)
        val retryPreparation = SessionCommand("com.blaze.player.RETRY_PREPARATION", Bundle.EMPTY)
        private val singletonLock = Any()
        @Volatile private var sharedPlayer: ExoPlayer? = null
        @Volatile private var sharedSession: MediaSession? = null
        fun playerInstance(): ExoPlayer? = sharedPlayer
        @Volatile var state: PlaybackState = PlaybackState()

    }
    private lateinit var session: MediaSession
    private var autoplayPending = false
    private var preparedMediaId: String? = null

    internal val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val player = sharedPlayer ?: return
            if (state == Player.STATE_BUFFERING) updateState(PlaybackState(PlaybackStatus.LOADING, mediaId = player.currentMediaItem?.mediaId))
            if (state == Player.STATE_READY) updateState(PlaybackState(PlaybackStatus.READY, mediaId = player.currentMediaItem?.mediaId))
            if (state == Player.STATE_READY && autoplayPending && preparedMediaId == player.currentMediaItem?.mediaId) {
                autoplayPending = false
                player.play()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // A failed prepare must never leave a later reconnect or retry with stale autoplay.
            autoplayPending = false
            updateState(PlaybackState(PlaybackStatus.ERROR, PlaybackErrorMapper.map(error), preparedMediaId))
        }
    }

    private fun updateState(value: PlaybackState) {
        state = value
    }

    override fun onCreate() {
        super.onCreate()
        val player = synchronized(singletonLock) {
            sharedPlayer ?: ExoPlayer.Builder(applicationContext).build().also { created ->
                sharedPlayer = created
                created.addListener(playerListener)
            }
        }
        session = synchronized(singletonLock) {
            sharedSession ?: MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.accept(SessionCommands.Builder()
                    .add(SessionCommand.COMMAND_PLAY_PAUSE)
                    .add(SessionCommand.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(prepareAndAutoplay)
                    .add(retryPreparation)
                    .build())
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand == prepareAndAutoplay || customCommand == retryPreparation) {
                    val item = args.getParcelable<MediaItem>("media_item")
                    // Reconnects or duplicate intent delivery must not reprepare the
                    // authoritative item. A genuinely new selection still replaces it.
                    if (item != null && (customCommand == retryPreparation || !(item.mediaId == preparedMediaId &&
                                session.player.currentMediaItem?.mediaId == item.mediaId)) {
                        autoplayPending = true
                        preparedMediaId = item.mediaId
                        updateState(PlaybackState(PlaybackStatus.LOADING, mediaId = item.mediaId))
                        session.player.setMediaItem(item)
                        session.player.prepare()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            }).build().also { sharedSession = it }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        // Keep the singleton alive across Activity/service reconnects. Process teardown releases it.
        super.onDestroy()
    }
}
