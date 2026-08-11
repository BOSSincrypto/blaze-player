package com.blaze.player.playback

/** The three states Android 13+ exposes to the app (without guessing from visibility). */
enum class NotificationPermissionState { NOT_YET_REQUESTED, GRANTED, DENIED }

enum class ForegroundNotificationPolicy { SHOW_MEDIA_CONTROLS, RUN_WITHOUT_SHADE_NOTIFICATION }

object NotificationPermissionPolicy {
    fun foregroundPolicy(apiLevel: Int, state: NotificationPermissionState): ForegroundNotificationPolicy =
        if (apiLevel < 33 || state == NotificationPermissionState.GRANTED) {
            ForegroundNotificationPolicy.SHOW_MEDIA_CONTROLS
        } else {
            // A denied POST_NOTIFICATIONS grant must never be represented as visible.
            ForegroundNotificationPolicy.RUN_WITHOUT_SHADE_NOTIFICATION
        }

    fun shouldRequest(apiLevel: Int, state: NotificationPermissionState, playbackRequested: Boolean): Boolean =
        apiLevel >= 33 && state == NotificationPermissionState.NOT_YET_REQUESTED && playbackRequested
}

/** Explicit, platform-independent semantics for the two ways a media notification disappears. */
enum class NotificationDismissal { TASK_REMOVED, EXPLICIT_STOP }

enum class DismissalResult { RETAIN_PLAYBACK, STOP_PLAYBACK }

object NotificationStopPolicy {
    fun result(dismissal: NotificationDismissal): DismissalResult = when (dismissal) {
        NotificationDismissal.TASK_REMOVED -> DismissalResult.RETAIN_PLAYBACK
        NotificationDismissal.EXPLICIT_STOP -> DismissalResult.STOP_PLAYBACK
    }
}
