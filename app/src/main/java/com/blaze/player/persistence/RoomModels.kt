package com.blaze.player.persistence

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "video_sources")
data class VideoSourceEntity(
    @androidx.room.PrimaryKey val identity: String,
    val uri: String,
    val title: String?,
    val durationMs: Long?,
    val local: Boolean,
    val persistable: Boolean,
    val metadata: String = ""
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "sourceIdentity"],
    indices = [androidx.room.Index(value = ["playlistId", "orderKey"], unique = true)]
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val sourceIdentity: String,
    val orderKey: Int
)

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: VideoSourceEntity)

    @Query("SELECT * FROM video_sources WHERE identity = :identity")
    suspend fun source(identity: String): VideoSourceEntity?

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deletePlaylistEntries(playlistId: Long)

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun playlist(playlistId: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY orderKey")
    fun entries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY orderKey")
    suspend fun entriesOnce(playlistId: Long): List<PlaylistEntryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: PlaylistEntryEntity): Long

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND sourceIdentity = :identity")
    suspend fun removeEntry(playlistId: Long, identity: String)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    @Query("UPDATE playlist_entries SET orderKey = :orderKey WHERE playlistId = :playlistId AND sourceIdentity = :identity")
    suspend fun updateOrder(playlistId: Long, identity: String, orderKey: Int)

    @Transaction
    suspend fun addEntry(playlistId: Long, identity: String): Boolean {
        val current = entriesOnce(playlistId)
        if (current.any { it.sourceIdentity == identity }) return false
        insertEntry(PlaylistEntryEntity(playlistId, identity, current.size))
        return true
    }

    @Transaction
    suspend fun removeAndCompact(playlistId: Long, identity: String): Boolean {
        val current = entriesOnce(playlistId)
        if (current.none { it.sourceIdentity == identity }) return false
        clearEntries(playlistId)
        current.filterNot { it.sourceIdentity == identity }
            .forEachIndexed { index, entry -> insertEntry(entry.copy(orderKey = index)) }
        return true
    }

    @Transaction
    suspend fun reorder(playlistId: Long, identity: String, newIndex: Int): Boolean {
        val current = entriesOnce(playlistId)
        if (newIndex !in current.indices || current.none { it.sourceIdentity == identity }) return false
        val moved = current.first { it.sourceIdentity == identity }
        val reordered = current.filterNot { it.sourceIdentity == identity }.toMutableList()
        reordered.add(newIndex, moved)
        clearEntries(playlistId)
        reordered.forEachIndexed { index, entry -> insertEntry(entry.copy(orderKey = index)) }
        return true
    }
}

@Database(entities = [VideoSourceEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class], version = 1, exportSchema = false)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun playbackDao(): PlaybackDao
}
