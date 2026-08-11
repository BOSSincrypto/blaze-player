package com.blaze.player.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Small durable record of request state. Permission truth always comes from the OS. */
class NotificationPermissionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)

    fun state(): NotificationPermissionState {
        if (Build.VERSION.SDK_INT < 33) return NotificationPermissionState.GRANTED
        if (!prefs.getBoolean(REQUESTED_KEY, false)) return NotificationPermissionState.NOT_YET_REQUESTED
        return if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) NotificationPermissionState.GRANTED else NotificationPermissionState.DENIED
    }

    fun recordRequestResult(granted: Boolean) {
        prefs.edit().putBoolean(REQUESTED_KEY, true).apply()
        // Do not persist "granted" as app-owned truth. Android can revoke it later.
        if (granted) prefs.edit().remove(GRANTED_KEY).apply()
    }

    companion object { private const val REQUESTED_KEY = "requested"; private const val GRANTED_KEY = "unused" }
}
