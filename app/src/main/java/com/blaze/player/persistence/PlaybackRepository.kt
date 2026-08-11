package com.blaze.player.persistence

import androidx.room.withTransaction
import com.blaze.player.source.NormalizedSource
import android.net.Uri
import java.net.URI

/** Durable Room facade. Playlist order is always re-numbered from zero. */
class PlaybackRepository(private val database: PlaybackDatabase) {
    private val dao = database.playbackDao()

    suspend fun saveSource(source: NormalizedSource, title: String? = null, durationMs: Long? = null, metadata: String = "") {
        require(durationMs == null || durationMs >= 0)
        val identity = SourceIdentity.canonical(source.uri)
        dao.upsertSource(VideoSourceEntity(identity, source.uri.toString(), title?.trim()?.takeIf { it.isNotEmpty() }, durationMs, source.local, source.persistable, metadata))
    }

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
        val normalized = authoritative.distinct()
        if (runtime.mediaIds() != normalized) runtime.replaceMediaIds(normalized)
    }
}
