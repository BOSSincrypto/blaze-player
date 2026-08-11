package com.blaze.player.playback

import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.room.Room
import android.net.Uri
import com.blaze.player.persistence.PlaybackDatabase
import com.blaze.player.persistence.PlaybackRepository
import com.blaze.player.persistence.RuntimePlaylist
import com.blaze.player.persistence.SourceIdentity
import com.blaze.player.persistence.CheckpointCoalescer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "blaze_playback"
        const val NOTIFICATION_CHANNEL_NAME = "Playback"
        const val MEDIA_ITEM_ARGUMENT_KEY = "media_item"
        val prepareAndAutoplay = SessionCommand("com.blaze.player.PREPARE_AUTOPLAY", Bundle.EMPTY)
        val retryPreparation = SessionCommand("com.blaze.player.RETRY_PREPARATION", Bundle.EMPTY)
        val setPlaybackSpeed = SessionCommand("com.blaze.player.SET_PLAYBACK_SPEED", Bundle.EMPTY)
        val stopPlayback = SessionCommand("com.blaze.player.STOP_PLAYBACK", Bundle.EMPTY)
        private val singletonLock = Any()
        @Volatile private var sharedPlayer: ExoPlayer? = null
        @Volatile private var sharedSession: MediaSession? = null
        fun playerInstance(): ExoPlayer? = sharedPlayer
        @Volatile var state: PlaybackState = PlaybackState()
        /** Process-local sink used by the service and its player listeners. */
        val performance = PerformanceInstrumentation()

        /** Decode the Bundleable payload used by custom session commands. */
        internal fun mediaItemFromArgs(args: Bundle): MediaItem? =
            args.getBundle(MEDIA_ITEM_ARGUMENT_KEY)?.let { bundle ->
                runCatching { MediaItem.fromBundle(bundle) }.getOrNull()
            }

    }
    private lateinit var session: MediaSession
    private val autoplayTransitions = AutoplayTransitionController()
    private var preparedMediaId: String? = null
    private var resumedMediaId: String? = null
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var playbackRepository: PlaybackRepository
    private data class Checkpoint(val identity: String, val positionMs: Long, val durationMs: Long?, val completed: Boolean, val nowMs: Long)
    private val checkpointCoalescer = CheckpointCoalescer<Checkpoint> { value ->
        playbackRepository.savePosition(value.identity, value.positionMs, value.durationMs, value.nowMs)
        playbackRepository.recordOpen(value.identity, value.nowMs, value.completed)
    }
    private val runtimeReconciler = com.blaze.player.persistence.RuntimePlaylistReconciler()
    private val runtimePlaylist = object : RuntimePlaylist {
        override fun mediaIds(): List<String> {
            val timeline = sharedPlayer?.currentTimeline ?: return emptyList()
            if (timeline.isEmpty) return emptyList()
            val window = Timeline.Window()
            return (0 until timeline.windowCount).mapNotNull { index ->
                timeline.getWindow(index, window).mediaItem.mediaId.takeIf { it.isNotEmpty() }
            }
        }
        override fun replaceMediaIds(ids: List<String>) {
            val player = sharedPlayer ?: return
            player.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).setUri(Uri.parse(it)).build() })
        }
    }

    internal val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            performance.boundary(PerformanceStage.FIRST_RENDERED_FRAME, currentSource())
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            if (isLoading && isHttpSource()) {
                performance.boundary(PerformanceStage.HTTP_REQUEST, currentSource())
            }
        }

        override fun onPositionDiscontinuity(reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) checkpoint(false)
            if (reason == Player.DISCONTINUITY_REASON_SEEK && isHttpSource()) {
                performance.boundary(PerformanceStage.HTTP_SEEK, currentSource())
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val player = sharedPlayer ?: return
            if (state == Player.STATE_BUFFERING) {
                if (isHttpSource()) performance.boundary(PerformanceStage.HTTP_BUFFER, currentSource())
                updateState(PlaybackState(PlaybackStatus.LOADING, mediaId = player.currentMediaItem?.mediaId))
            }
            if (state == Player.STATE_READY) updateState(PlaybackState(PlaybackStatus.READY, mediaId = player.currentMediaItem?.mediaId))
            if (state == Player.STATE_READY) {
                checkpoint(false)
                val item = player.currentMediaItem
                if (item != null && resumedMediaId != item.mediaId) {
                    resumedMediaId = item.mediaId
                    val identity = SourceIdentity.canonical(item.localConfiguration?.uri ?: Uri.EMPTY)
                    settingsScope.launch {
                        val history = playbackRepository.history().firstOrNull { it.sourceIdentity == identity }
                        val saved = playbackRepository.position(identity)
                        val resume = PlaybackRepository.resumePosition(saved, history?.completed == true)
                        withContext(Dispatchers.Main) {
                            if (sharedPlayer?.currentMediaItem?.mediaId == item.mediaId && resume > 0L) {
                                sharedPlayer?.seekTo(resume)
                            }
                        }
                    }
                }
                autoplayTransitions.prepared(player.currentMediaItem?.mediaId.orEmpty()).forEach { effect ->
                    if (effect == AutoplayTransitionController.Effect.PLAY) player.play()
                }
            }
        }

        private fun currentSource(): String? = sharedPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        private fun isHttpSource(): Boolean = currentSource()?.let { it.startsWith("http://") || it.startsWith("https://") } == true

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // A failed prepare must never leave a later reconnect or retry with stale autoplay.
            autoplayTransitions.failed(preparedMediaId.orEmpty())
            updateState(PlaybackState(PlaybackStatus.ERROR, PlaybackErrorMapper.map(error), preparedMediaId))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) checkpoint(false)
        }
    }

    private fun checkpoint(forceCompletion: Boolean) {
        val player = sharedPlayer ?: return
        val item = player.currentMediaItem ?: return
        val identity = SourceIdentity.canonical(item.localConfiguration?.uri ?: return)
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
        val completed = forceCompletion || PlaybackRepository.isCompleted(position, duration)
        val now = System.currentTimeMillis()
        settingsScope.launch {
            checkpointCoalescer.submit(Checkpoint(identity, position, duration, completed, now))
            checkpointCoalescer.flush()
        }
    }

    /** Reconciles the active Media3 order from Room without exposing partial mutations. */
    internal fun reconcileActivePlaylist(playlistId: Long) {
        settingsScope.launch {
            playbackRepository.reconcileRuntime(playlistId, runtimePlaylist)
            runtimeReconciler.reconcile(runtimePlaylist.mediaIds(), runtimePlaylist)
        }
    }

    private fun updateState(value: PlaybackState) {
        state = value
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        playbackRepository = PlaybackRepository(
            Room.databaseBuilder(applicationContext, PlaybackDatabase::class.java, "playback.db")
                .addMigrations(PlaybackDatabase.MIGRATION_1_2)
                .build()
        )
        val player = synchronized(singletonLock) {
            sharedPlayer ?: ExoPlayer.Builder(applicationContext)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build().also { created ->
                sharedPlayer = created
                created.addListener(playerListener)
            }
        }
        settingsScope.launch {
            PlaybackSpeedStore.observe(applicationContext).distinctUntilChanged().collect { speed ->
                withContext(Dispatchers.Main) { sharedPlayer?.setPlaybackSpeed(speed) }
            }
        }
        session = synchronized(singletonLock) {
            sharedSession ?: MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val playerCommands = Player.Commands.Builder()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .build()
                val sessionCommands = SessionCommands.Builder()
                    .add(prepareAndAutoplay)
                    .add(retryPreparation)
                    .add(setPlaybackSpeed)
                    .add(stopPlayback)
                    .build()
                return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                val item = mediaItemFromArgs(args)
                performance.boundary(PerformanceStage.DISPATCH, item?.localConfiguration?.uri?.toString())
                if (customCommand == setPlaybackSpeed) {
                    setGlobalPlaybackSpeed(args.getFloat("speed", PlaybackSpeedStore.DEFAULT))
                    performance.boundary(PerformanceStage.POSITION_ACKNOWLEDGEMENT)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand == stopPlayback) {
                    // Stop is explicit: checkpoint first, then stop the service-owned player.
                    checkpoint(false)
                    session.player.stop()
                    updateState(PlaybackState())
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand == prepareAndAutoplay || customCommand == retryPreparation) {
                    val requestedContext = args.getString("autoplay_context")
                    val context = requestedContext
                        ?.let { runCatching { AutoplayContext.valueOf(it) }.getOrNull() }
                        ?: if (customCommand == retryPreparation) AutoplayContext.RETRY else AutoplayContext.PICKER
                    // An unrecognised explicit context is never treated as a new
                    // picker request, preventing stale callers from autoplaying.
                    val shouldAutoplay = requestedContext == null ||
                        runCatching { AutoplayPolicy.allows(AutoplayRequest(context, item?.mediaId.orEmpty())) }
                            .getOrDefault(false)
                    // Reconnects or duplicate intent delivery must not reprepare the
                    // authoritative item. A genuinely new selection still replaces it.
                    if (item != null && (customCommand == retryPreparation || !(item.mediaId == preparedMediaId &&
                                session.player.currentMediaItem?.mediaId == item.mediaId))) {
                        preparedMediaId = item.mediaId
                        val effects = autoplayTransitions.request(item.mediaId, context, retry = customCommand == retryPreparation)
                        if (effects.contains(AutoplayTransitionController.Effect.PREPARE)) {
                            updateState(PlaybackState(PlaybackStatus.LOADING, mediaId = item.mediaId))
                            // Content providers may block while checking a revoked
                            // grant. Keep that boundary off the service command thread.
                            settingsScope.launch {
                                val readable = withContext(Dispatchers.IO) {
                                    if (item.localConfiguration?.uri?.scheme == "content") {
                                        runCatching {
                                            applicationContext.contentResolver.openAssetFileDescriptor(
                                                item.localConfiguration!!.uri, "r"
                                            )?.use { true } == true
                                        }.getOrDefault(false)
                                    } else true
                                }
                                withContext(Dispatchers.Main) {
                                    if (!readable) {
                                        autoplayTransitions.failed(item.mediaId)
                                        updateState(PlaybackState(PlaybackStatus.ERROR, PlaybackError(
                                            "Video unavailable",
                                            "This video is no longer accessible. Re-select it from storage.",
                                            false
                                        ), item.mediaId))
                                    } else if (preparedMediaId == item.mediaId) {
                                        session.player.setMediaItem(item)
                                        session.player.prepare()
                                    }
                                }
                                if (readable) {
                                    val identity = SourceIdentity.canonical(item.localConfiguration?.uri ?: Uri.EMPTY)
                                    playbackRepository.recordOpen(identity, System.currentTimeMillis())
                                }
                            }
                        }
                    }
                }
                performance.boundary(PerformanceStage.POSITION_ACKNOWLEDGEMENT, item?.localConfiguration?.uri?.toString())
                checkpoint(false)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            }).build().also { sharedSession = it }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        // Keep the singleton alive across Activity/service reconnects. Process teardown releases it.
        checkpoint(false)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Removing the task is not a stop request. The MediaSessionService and
        // its notification remain authoritative for active background playback.
        checkpoint(false)
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Media playback controls" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** Updates the global setting; the DataStore observer applies it to current and future items. */
    fun setGlobalPlaybackSpeed(value: Float) {
        val speed = PlaybackSpeedStore.normalize(value) ?: return
        settingsScope.launch { PlaybackSpeedStore.save(applicationContext, speed) }
    }
}
