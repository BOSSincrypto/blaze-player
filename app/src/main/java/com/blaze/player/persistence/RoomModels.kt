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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @androidx.room.PrimaryKey val sourceIdentity: String,
    val lastPlayedAtMs: Long,
    val completed: Boolean = false
)

@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @androidx.room.PrimaryKey val sourceIdentity: String,
    val positionMs: Long,
    val acknowledgedAtMs: Long
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(value: PlaybackHistoryEntity)

    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAtMs DESC, sourceIdentity ASC")
    suspend fun history(): List<PlaybackHistoryEntity>

    @Query("DELETE FROM playback_history WHERE sourceIdentity = :identity")
    suspend fun clearHistory(identity: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAllHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(value: PlaybackPositionEntity)

    @Query("SELECT * FROM playback_positions WHERE sourceIdentity = :identity")
    suspend fun position(identity: String): PlaybackPositionEntity?

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

@Database(entities = [VideoSourceEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, PlaybackHistoryEntity::class, PlaybackPositionEntity::class], version = 2, exportSchema = false)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun playbackDao(): PlaybackDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_history (sourceIdentity TEXT NOT NULL PRIMARY KEY, lastPlayedAtMs INTEGER NOT NULL, completed INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_positions (sourceIdentity TEXT NOT NULL PRIMARY KEY, positionMs INTEGER NOT NULL, acknowledgedAtMs INTEGER NOT NULL)")
            }
        }
    }
}
