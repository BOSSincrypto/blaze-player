package com.blaze.player

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.blaze.player.playback.PlaybackService
import com.blaze.player.ui.PlaybackControls
import com.blaze.player.source.LocalSourceAccess
import com.blaze.player.source.SourceNormalizer
import android.content.Intent
import android.net.Uri
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var lastIntentKey: String? = null
    private var lastMediaItem: androidx.media3.common.MediaItem? = null
    private lateinit var statusView: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, 0) }
        val controls = PlaybackControls(this)
        statusView = android.widget.TextView(this).apply {
            text = ""
            setPadding(24, 12, 24, 12)
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
        } }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun handleIntent(input: Intent?, c: MediaController) {
        if (input == null || (input.action != Intent.ACTION_VIEW && input.action != Intent.ACTION_SEND)) return
        val intentKey = listOf(input.action, input.data?.toString(), input.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString(), input.getStringExtra(Intent.EXTRA_TEXT)).joinToString("|")
        if (intentKey == lastIntentKey) return
        lastIntentKey = intentKey
        val result = SourceNormalizer.fromIntent(input, object : LocalSourceAccess {
            override fun canRead(uri: Uri) = true
            override fun takePersistableReadPermission(uri: Uri) = false
        })
        if (result is com.blaze.player.source.SourceResult.Accepted) {
            lastMediaItem = result.mediaItem
            showLoading()
            c.sendCustomCommand(PlaybackService.prepareAndAutoplay, android.os.Bundle().apply {
                putParcelable("media_item", result.mediaItem)
            })
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
                    putParcelable("media_item", item)
                })
            }
        } else {
            statusView.setOnClickListener(null)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { handleIntent(intent, it) }
    }

    override fun onDestroy() {
        playerView.player = null
        controller?.let { MediaController.releaseFuture(controllerFuture) }
        super.onDestroy()
    }
}
