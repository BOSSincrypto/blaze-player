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
        @Volatile private var sharedPlayer: ExoPlayer? = null
        @Volatile private var sharedSession: MediaSession? = null
        fun playerInstance(): ExoPlayer? = sharedPlayer
    }
    private lateinit var session: MediaSession
    private var autoplayPending = false

    override fun onCreate() {
        super.onCreate()
        val player = sharedPlayer ?: ExoPlayer.Builder(this).build().also {
            sharedPlayer = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && autoplayPending) {
                        autoplayPending = false
                        it.play()
                    }
                }
            })
        }
        session = sharedSession ?: MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.accept(SessionCommands.Builder()
                    .add(SessionCommand.COMMAND_PLAY_PAUSE)
                    .add(prepareAndAutoplay)
                    .build())
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand == prepareAndAutoplay) {
                    val item = args.getParcelable<MediaItem>("media_item")
                    if (item != null && session.player.currentMediaItem?.mediaId != item.mediaId) {
                        autoplayPending = true
                        session.player.setMediaItem(item)
                        session.player.prepare()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build().also { sharedSession = it }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        // Keep the singleton alive across Activity/service reconnects. Process teardown releases it.
        super.onDestroy()
    }
}
