package dev.bmg.edgepanel.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {

    // Observe all clips: pinned first, then by recency
    @Query("""
        SELECT * FROM clip_history 
        ORDER BY isPinned DESC, copiedAt DESC
    """)
    fun observeAll(): Flow<List<ClipEntity>>

    // One-shot read for cold starts (no Flow needed)
    @Query("""
        SELECT * FROM clip_history 
        ORDER BY isPinned DESC, copiedAt DESC
    """)
    suspend fun getAll(): List<ClipEntity>

    // Insert or replace if same text exists
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: ClipEntity): Long

    // Move existing entry to top by updating its timestamp
    @Query("UPDATE clip_history SET copiedAt = :ts WHERE text = :text")
    suspend fun updateTimestamp(text: String, ts: Long = System.currentTimeMillis())

    // Check if text already exists
    @Query("SELECT COUNT(*) FROM clip_history WHERE text = :text")
    suspend fun exists(text: String): Int

    // Delete a single entry
    @Delete
    suspend fun delete(clip: ClipEntity)

    // Delete by text
    @Query("DELETE FROM clip_history WHERE text = :text")
    suspend fun deleteByText(text: String)

    // Evict oldest unpinned rows beyond the 50-item cap
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

    // Clear all unpinned
    @Query("DELETE FROM clip_history WHERE isPinned = 0")
    suspend fun clearUnpinned()

    // Toggle pin
    @Query("UPDATE clip_history SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)
}