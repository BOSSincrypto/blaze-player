package com.blaze.player

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply { layoutParams = ViewGroup.LayoutParams(-1, 0) }
        val controls = PlaybackControls(this)
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(playerView, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls)
        })
        controllerFuture = MediaController.Builder(this, SessionToken(this, PlaybackService::class.java)).buildAsync()
        controllerFuture.addListener({ controller = controllerFuture.get().also { c ->
            playerView.player = c
            controls.bind(c)
            handleIntent(intent, c)
        } }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun handleIntent(input: Intent?, c: MediaController) {
        if (input == null || (input.action != Intent.ACTION_VIEW && input.action != Intent.ACTION_SEND)) return
        val result = SourceNormalizer.fromIntent(input, object : LocalSourceAccess {
            override fun canRead(uri: Uri) = true
            override fun takePersistableReadPermission(uri: Uri) = false
        })
        if (result is com.blaze.player.source.SourceResult.Accepted) {
            c.sendCustomCommand(PlaybackService.prepareAndAutoplay, android.os.Bundle().apply {
                putParcelable("media_item", result.mediaItem)
            })
        }
    }

    override fun onDestroy() {
        playerView.player = null
        controller?.let { MediaController.releaseFuture(controllerFuture) }
        super.onDestroy()
    }
}
