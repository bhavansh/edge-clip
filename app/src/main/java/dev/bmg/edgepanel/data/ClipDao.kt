package dev.bmg.edgepanel.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    // Check if text already exists
    @Query("SELECT COUNT(*) FROM clip_history WHERE text = :text")
    suspend fun exists(text: String): Int

    @Query("SELECT * FROM clip_history ORDER BY isPinned DESC, copiedAt DESC")
    fun observeAll(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clip_history ORDER BY isPinned DESC, copiedAt DESC")
    suspend fun getAll(): List<ClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: ClipEntity): Long

    @Query("UPDATE clip_history SET copiedAt = :ts WHERE text = :text")
    suspend fun updateTimestamp(text: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM clip_history WHERE text = :text AND type = 'TEXT'")
    suspend fun existsText(text: String): Int

    // Images deduplicated by their hash-based filenames
    @Query("SELECT COUNT(*) FROM clip_history WHERE imagePath = :path")
    suspend fun existsImage(path: String): Int

    @Query("UPDATE clip_history SET copiedAt = :ts WHERE imagePath = :path")
    suspend fun updateTimestampImage(path: String, ts: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(clip: ClipEntity)

    @Query("DELETE FROM clip_history WHERE text = :text")
    suspend fun deleteByText(text: String)

    @Query("""
        DELETE FROM clip_history 
        WHERE isPinned = 0
        AND id NOT IN (
            SELECT id FROM clip_history 
            WHERE isPinned = 0
            ORDER BY copiedAt DESC 
            LIMIT :cap
        )
    """)
    suspend fun evictBeyondCap(cap: Int = 50)

    // Returns paths of image files that were evicted so we can delete them
    @Query("""
        SELECT imagePath FROM clip_history
        WHERE isPinned = 0
        AND imagePath IS NOT NULL
        AND id NOT IN (
            SELECT id FROM clip_history
            WHERE isPinned = 0
            ORDER BY copiedAt DESC
            LIMIT :cap
        )
    """)
    suspend fun evictedImagePaths(cap: Int = 50): List<String>

    @Query("DELETE FROM clip_history WHERE isPinned = 0")
    suspend fun clearUnpinned()

    @Query("SELECT imagePath FROM clip_history WHERE isPinned = 0 AND imagePath IS NOT NULL")
    suspend fun allUnpinnedImagePaths(): List<String>

    @Query("UPDATE clip_history SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

}