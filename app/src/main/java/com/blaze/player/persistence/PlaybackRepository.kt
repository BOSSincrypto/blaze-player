package com.blaze.player.persistence

import androidx.room.withTransaction
import com.blaze.player.source.NormalizedSource
import android.net.Uri
import java.net.URI

/** Durable Room facade. Playlist order is always re-numbered from zero. */
class PlaybackRepository(private val database: PlaybackDatabase) {
    private val dao = database.playbackDao()
    private val runtimeReconciler = RuntimePlaylistReconciler()

    suspend fun saveSource(source: NormalizedSource, title: String? = null, durationMs: Long? = null, metadata: String = "") {
        require(durationMs == null || durationMs >= 0)
        val identity = SourceIdentity.canonical(source.uri)
        val existing = dao.source(identity)
        // A checkpoint or a later metadata probe may only know part of the
        // record. Do not erase durable metadata when upserting that partial
        // observation.
        dao.upsertSource(
            VideoSourceEntity(
                identity = identity,
                uri = source.uri.toString(),
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.title,
                durationMs = durationMs ?: existing?.durationMs,
                local = source.local,
                persistable = source.persistable || existing?.persistable == true,
                metadata = PrivacyRedactor.metadata(metadata.ifBlank { existing?.metadata.orEmpty() })
            )
        )
    }

    suspend fun source(uri: Uri): VideoSourceEntity? = dao.source(SourceIdentity.canonical(uri))

    suspend fun createPlaylist(name: String): Long = dao.insertPlaylist(PlaylistEntity(name = name.trim().ifEmpty { "Untitled playlist" }))

    suspend fun renamePlaylist(id: Long, name: String): Boolean = dao.playlist(id)?.let {
        dao.updatePlaylist(it.copy(name = name.trim().ifEmpty { "Untitled playlist" })); true
    } ?: false

    suspend fun deletePlaylist(id: Long): Boolean = database.withTransaction {
        if (dao.playlist(id) == null) false else { dao.deletePlaylistEntries(id); dao.deletePlaylist(id); true }
    }

    suspend fun addToPlaylist(playlistId: Long, sourceIdentity: String): Boolean = database.withTransaction {
        require(dao.playlist(playlistId) != null) { "Unknown playlist" }
        require(dao.source(sourceIdentity) != null) { "Unknown source" }
        dao.addEntry(playlistId, sourceIdentity)
    }

    suspend fun removeFromPlaylist(playlistId: Long, sourceIdentity: String): Boolean = database.withTransaction {
        require(dao.playlist(playlistId) != null) { "Unknown playlist" }
        dao.removeAndCompact(playlistId, sourceIdentity)
    }

    suspend fun reorderPlaylist(playlistId: Long, sourceIdentity: String, newIndex: Int): Boolean = database.withTransaction {
        require(dao.playlist(playlistId) != null) { "Unknown playlist" }
        dao.reorder(playlistId, sourceIdentity, newIndex)
    }

    suspend fun orderedEntries(playlistId: Long): List<PlaylistEntryEntity> = dao.entriesOnce(playlistId)

    suspend fun recordOpen(sourceIdentity: String, nowMs: Long, completed: Boolean = false) {
        require(sourceIdentity.isNotBlank())
        val wasCompleted = dao.history().firstOrNull { it.sourceIdentity == sourceIdentity }?.completed == true
        dao.upsertHistory(PlaybackHistoryEntity(sourceIdentity, nowMs, wasCompleted || completed))
    }

    suspend fun markCompleted(sourceIdentity: String, nowMs: Long) {
        val existing = dao.history().firstOrNull { it.sourceIdentity == sourceIdentity }
        recordOpen(sourceIdentity, nowMs, existing?.completed != false)
    }

    suspend fun history(): List<PlaybackHistoryEntity> = dao.history()

    suspend fun clearHistory(sourceIdentity: String) = dao.clearHistory(sourceIdentity)
    suspend fun clearAllHistory() = dao.clearAllHistory()

    suspend fun savePosition(sourceIdentity: String, positionMs: Long, durationMs: Long?, nowMs: Long) {
        require(positionMs >= 0) { "Position must be nonnegative" }
        require(durationMs == null || durationMs >= 0) { "Duration must be nonnegative" }
        val bounded = if (durationMs != null && durationMs > 0) positionMs.coerceAtMost(durationMs) else positionMs
        dao.upsertPosition(PlaybackPositionEntity(sourceIdentity, bounded, nowMs))
    }

    suspend fun position(sourceIdentity: String): PlaybackPositionEntity? = dao.position(sourceIdentity)

    companion object {
        fun isCompleted(positionMs: Long, durationMs: Long?): Boolean {
            if (positionMs < 0) return false
            if (durationMs == null || durationMs <= 0) return false
            val remaining = (durationMs - positionMs).coerceAtLeast(0)
            return remaining <= 30_000L || positionMs >= durationMs * 0.95
        }

        fun resumePosition(position: PlaybackPositionEntity?, completed: Boolean): Long =
            if (completed) 0L else position?.positionMs?.coerceAtLeast(0L) ?: 0L
    }

    /** Adds a canonical source to a playlist, keeping identity rules in one place. */
    suspend fun addToPlaylist(playlistId: Long, source: NormalizedSource): Boolean =
        addToPlaylist(playlistId, SourceIdentity.canonical(source.uri))

    /**
     * Applies the Room order as one runtime replacement. Room is read first and
     * is never changed by this operation, so a player failure cannot leave a
     * partially-mutated durable playlist. A later reconciliation can retry.
     */
    suspend fun reconcileRuntime(playlistId: Long, runtime: RuntimePlaylist): Boolean {
        val authoritative = orderedEntries(playlistId).map { it.sourceIdentity }
        val before = runtime.mediaIds()
        runtimeReconciler.reconcile(authoritative, runtime)
        return before != runtime.mediaIds()
    }
}

object SourceIdentity {
    fun canonical(uri: Uri): String {
        if (uri.scheme.equals("content", true)) return uri.toString()
        val parsed = runCatching { URI(uri.toString()) }.getOrNull() ?: return uri.toString()
        val scheme = parsed.scheme?.lowercase() ?: return uri.toString()
        val host = parsed.host?.lowercase() ?: return uri.toString()
        val port = if ((scheme == "http" && parsed.port == 80) || (scheme == "https" && parsed.port == 443)) -1 else parsed.port
        return URI(scheme, parsed.userInfo, host, port, parsed.path, parsed.query, parsed.fragment).toString()
    }
}

/** Minimal player boundary keeps Room authoritative and makes reconciliation testable. */
interface RuntimePlaylist {
    fun mediaIds(): List<String>
    fun replaceMediaIds(ids: List<String>)
}

class RuntimePlaylistReconciler {
    fun reconcile(authoritative: List<String>, runtime: RuntimePlaylist) {
        // Defensive runtime cleanup is kept here for stale Media3 state. Room
        // itself enforces one membership per source, so normal reconciliation
        // receives an already-unique authoritative list.
        val normalized = authoritative.distinct()
        if (runtime.mediaIds() != normalized) runtime.replaceMediaIds(normalized)
    }
}
