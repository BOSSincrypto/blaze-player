package com.blaze.player.persistence

import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The durable portion of a cold start. It deliberately contains no play intent. */
data class DurableRecoverySnapshot(
    val sourceIdentity: String?,
    val orderedSourceIdentities: List<String>,
    val positionMs: Long,
    val completed: Boolean,
    val speed: Float
)

object PlaybackRecoveryPolicy {
    fun coldStart(
        sourceIdentity: String?,
        orderedSourceIdentities: List<String>,
        position: PlaybackPositionEntity?,
        completed: Boolean,
        speed: Float
    ): DurableRecoverySnapshot = DurableRecoverySnapshot(
        sourceIdentity = sourceIdentity,
        orderedSourceIdentities = orderedSourceIdentities.distinct(),
        positionMs = PlaybackRepository.resumePosition(position, completed),
        completed = completed,
        speed = speed
    )

    /** Process death creates a new runtime identity; all other outcomes are explicit. */
    fun lifecycleOutcome(event: LifecycleEvent, serviceAlive: Boolean): LifecycleOutcome = when (event) {
        LifecycleEvent.ACTIVITY_RECREATED, LifecycleEvent.CONTROLLER_RECONNECTED,
        LifecycleEvent.PIP_ENTERED, LifecycleEvent.SURFACE_CHANGED ->
            if (serviceAlive) LifecycleOutcome.RETAIN_PLAYER else LifecycleOutcome.RESTORE_DURABLE
        LifecycleEvent.BACKGROUND, LifecycleEvent.SERVICE_RESTARTED ->
            if (serviceAlive) LifecycleOutcome.RETAIN_PLAYER else LifecycleOutcome.RESTORE_DURABLE
        LifecycleEvent.PROCESS_DIED, LifecycleEvent.COLD_LAUNCH -> LifecycleOutcome.NEW_PLAYER_FROM_DURABLE
    }
}

enum class LifecycleEvent {
    ACTIVITY_RECREATED, CONTROLLER_RECONNECTED, PIP_ENTERED, SURFACE_CHANGED,
    BACKGROUND, SERVICE_RESTARTED, PROCESS_DIED, COLD_LAUNCH
}

enum class LifecycleOutcome { RETAIN_PLAYER, RESTORE_DURABLE, NEW_PLAYER_FROM_DURABLE }

/** Coalesces checkpoint bursts and serializes writes, so the latest acknowledged value wins. */
class CheckpointCoalescer<T>(private val write: suspend (T) -> Unit) {
    private val mutex = Mutex()
    private var pending: T? = null

    suspend fun submit(value: T) {
        mutex.withLock { pending = value }
    }

    suspend fun flush() {
        val value = mutex.withLock { pending.also { pending = null } } ?: return
        write(value)
    }
}

enum class StoreOwner { ROOM, DATASTORE }

object PrivacyRedactor {
    /** Safe diagnostics retain host/scheme only and never retain local path details. */
    fun source(value: String?): String = value?.let {
        runCatching {
            val uri = Uri.parse(it)
            when (uri.scheme?.lowercase()) {
                "content" -> "content://redacted"
                "http", "https" -> "${uri.scheme!!.lowercase()}://${uri.host ?: "redacted-host"}"
                else -> "redacted-source"
            }
        }.getOrDefault("redacted-source")
    } ?: "redacted-source"

    fun metadata(value: String?): String = value.orEmpty()
        .replace(Regex("(?i)(authorization|cookie|token|password|secret|api[_-]?key)\\s*[:=]\\s*[^,;\\s]+"), "[REDACTED]")
        .replace(Regex("(?i)(https?://[^\\s?]+)[?][^\\s]+"), "$1")
}
