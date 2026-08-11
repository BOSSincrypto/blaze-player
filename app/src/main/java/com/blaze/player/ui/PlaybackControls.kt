package com.blaze.player.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.media3.common.Player

class PlaybackControls(context: Context) : LinearLayout(context) {
    private var player: Player? = null
    private val toggle = Button(context).apply { text = "Play" }
    private val back = Button(context).apply { text = "-10s" }
    private val forward = Button(context).apply { text = "+10s" }
    private val speedSelector = Spinner(context)
    private var speedChanged: ((Float) -> Unit)? = null
    init {
        orientation = HORIZONTAL
        addView(back)
        addView(toggle)
        addView(forward)
        speedSelector.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            PlaybackSpeed.presets.map { "${it}x" })
        addView(speedSelector)
        toggle.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        back.setOnClickListener { player?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) } }
        forward.setOnClickListener {
            player?.let { p ->
                val target = p.currentPosition + 10_000L
                p.seekTo(if (p.duration > 0L) target.coerceAtMost(p.duration) else target)
            }
        }
    }
    fun bind(value: Player) {
        player = value
        toggle.text = if (value.isPlaying) "Pause" else "Play"
        speedSelector.setSelection(PlaybackSpeed.presets.indexOfFirst { it == value.playbackParameters.speed }.coerceAtLeast(0))
        speedSelector.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                speedChanged?.invoke(PlaybackSpeed.presets[position])
            }
        })
        value.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                toggle.text = if (isPlaying) "Pause" else "Play"
            }
        })
    }

    fun onSpeedChanged(listener: (Float) -> Unit) { speedChanged = listener }
}
