package com.blaze.player

import android.os.Bundle
import android.os.Build
import android.view.ViewGroup
import android.view.MotionEvent
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.blaze.player.playback.PlaybackService
import com.blaze.player.ui.PlaybackControls
import com.blaze.player.ui.PlaybackGestureHandler
import com.blaze.player.ui.PlaybackMath
import com.blaze.player.source.ContentResolverSourceAccess
import com.blaze.player.source.SourceNormalizer
import android.content.Intent
import android.net.Uri
import android.app.PictureInPictureParams
import com.google.common.util.concurrent.ListenableFuture
import com.blaze.player.playback.NotificationPermissionPolicy
import com.blaze.player.playback.NotificationPermissionState
import com.blaze.player.playback.NotificationPermissionStore

class MainActivity : ComponentActivity() {
    private lateinit var notificationPermissionStore: NotificationPermissionStore
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPermissionStore.recordRequestResult(granted)
    }
    private lateinit var playerView: PlayerView
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var lastIntentKey: String? = null
    private var lastMediaItem: androidx.media3.common.MediaItem? = null
    private lateinit var statusView: android.widget.TextView
    private lateinit var gestures: PlaybackGestureHandler
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            showError(com.blaze.player.playback.PlaybackError("Picker", "No video was selected.", false))
            return@registerForActivityResult
        }
        preparePickerUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionStore = NotificationPermissionStore(this)
        playerView = PlayerView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, 0) }
        val controls = PlaybackControls(this)
        controls.onSpeedChanged { speed ->
            // The service is the only owner of persistence and player state. A custom
            // session command keeps this UI independent from service implementation details.
            controller?.sendCustomCommand(PlaybackService.setPlaybackSpeed, Bundle().apply { putFloat("speed", speed) })
        }
        statusView = android.widget.TextView(this).apply {
            text = ""
            setPadding(24, 12, 24, 12)
        }
        gestures = PlaybackGestureHandler(
            duration = { controller?.duration ?: 0L },
            position = { controller?.currentPosition ?: 0L },
            brightness = { window.attributes.screenBrightness.takeIf { it in 0f..1f } ?: 0.5f },
            volume = {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max == 0) 0f else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            },
            seek = { controller?.seekTo(it) },
            setBrightness = { value -> window.attributes = window.attributes.apply { screenBrightness = PlaybackMath.clamp(value) } },
            setVolume = { value ->
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max > 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (PlaybackMath.clamp(value) * max).toInt(), 0)
            },
            showOverlay = { statusView.text = it },
            clearOverlay = { if (controller?.playbackState == Player.STATE_READY) statusView.text = "" }
        )
        playerView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> gestures.down(event.x, event.y, playerView.width.toFloat(), playerView.height.toFloat())
                MotionEvent.ACTION_MOVE -> gestures.move(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestures.up()
            }
            true
        }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(playerView, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            addView(statusView)
            addView(controls)
        })
        controllerFuture = MediaController.Builder(this, SessionToken(this, PlaybackService::class.java)).buildAsync()
        controllerFuture.addListener({ controller = controllerFuture.get().also { c ->
            playerView.player = c
            controls.bind(c)
            c.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> showLoading()
                        Player.STATE_READY -> showReady()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val mapped = com.blaze.player.playback.PlaybackErrorMapper.map(error)
                    showError(mapped)
                }
            })
            handleIntent(intent, c)
            val state = notificationPermissionStore.state()
            if (NotificationPermissionPolicy.shouldRequest(android.os.Build.VERSION.SDK_INT, state, intent?.action != Intent.ACTION_MAIN)) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (intent?.action == Intent.ACTION_MAIN) picker.launch(arrayOf("video/*"))
        } }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun preparePickerUri(uri: Uri) {
        val access = ContentResolverSourceAccess(contentResolver)
        val result = SourceNormalizer.fromPicker(
            uri,
            access,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        val c = controller ?: return
        if (result is com.blaze.player.source.SourceResult.Accepted) {
            requestNotificationPermissionForPlayback()
            lastMediaItem = result.mediaItem
            showLoading()
            c.sendCustomCommand(PlaybackService.prepareAndAutoplay, Bundle().apply {
                putBundle(PlaybackService.MEDIA_ITEM_ARGUMENT_KEY, result.mediaItem.toBundle())
                putString("autoplay_context", "PICKER")
            })
        } else {
            showError(com.blaze.player.playback.PlaybackError("Video unavailable", "The selected video cannot be opened. Choose another file.", false))
        }
    }

    private fun requestNotificationPermissionForPlayback() {
        val state = notificationPermissionStore.state()
        if (NotificationPermissionPolicy.shouldRequest(Build.VERSION.SDK_INT, state, true)) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntent(input: Intent?, c: MediaController) {
        if (input == null || (input.action != Intent.ACTION_VIEW && input.action != Intent.ACTION_SEND)) return
        val intentKey = listOf(input.action, input.data?.toString(), input.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString(), input.getStringExtra(Intent.EXTRA_TEXT)).joinToString("|")
        if (intentKey == lastIntentKey) return
        lastIntentKey = intentKey
        val result = SourceNormalizer.fromIntent(input, ContentResolverSourceAccess(contentResolver))
        if (result is com.blaze.player.source.SourceResult.Accepted) {
            lastMediaItem = result.mediaItem
            showLoading()
            c.sendCustomCommand(PlaybackService.prepareAndAutoplay, android.os.Bundle().apply {
                putBundle(PlaybackService.MEDIA_ITEM_ARGUMENT_KEY, result.mediaItem.toBundle())
                putString("autoplay_context", "SHARE")
            })
        } else if (result is com.blaze.player.source.SourceResult.Rejected && input.data?.scheme == "content") {
            showError(com.blaze.player.playback.PlaybackError("Video unavailable", "This shared video is no longer readable. Ask the sender to share it again.", false))
        }
    }

    private fun showLoading() {
        statusView.text = "Loading…"
        statusView.isClickable = false
        statusView.setOnClickListener(null)
    }

    private fun showReady() {
        statusView.text = ""
        statusView.isClickable = false
        statusView.setOnClickListener(null)
    }

    private fun showError(error: com.blaze.player.playback.PlaybackError) {
        statusView.text = if (error.canRetry) {
            "${error.title}: ${error.message} Tap to retry."
        } else {
            "${error.title}: ${error.message}"
        }
        statusView.isClickable = error.canRetry
        if (error.canRetry) {
            statusView.setOnClickListener {
                val item = lastMediaItem ?: return@setOnClickListener
                showLoading()
                controller?.sendCustomCommand(PlaybackService.retryPreparation, Bundle().apply {
                    putBundle(PlaybackService.MEDIA_ITEM_ARGUMENT_KEY, item.toBundle())
                })
            }
        } else {
            statusView.setOnClickListener(null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { handleIntent(intent, it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // PiP changes only the Activity surface. Playback remains owned by the
        // MediaSessionService and its controller-backed player.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            controller?.isPlaying == true && !isInPictureInPictureMode
        ) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onDestroy() {
        playerView.player = null
        controller?.let { MediaController.releaseFuture(controllerFuture) }
        super.onDestroy()
    }
}
