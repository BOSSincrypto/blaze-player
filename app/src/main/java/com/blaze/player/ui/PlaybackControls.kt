package com.blaze.player.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.Button
import androidx.media3.common.Player

class PlaybackControls(context: Context) : LinearLayout(context) {
    private var player: Player? = null
    private val toggle = Button(context).apply { text = "Play" }
    init { orientation = HORIZONTAL; addView(toggle); toggle.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } } }
    fun bind(value: Player) { player = value; toggle.text = if (value.isPlaying) "Pause" else "Play" }
}
