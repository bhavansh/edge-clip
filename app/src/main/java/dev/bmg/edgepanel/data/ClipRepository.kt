package dev.bmg.edgepanel.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ClipRepository private constructor(private val dao: ClipDao) {

    // Live stream for EdgePanelService UI
    val clips: Flow<List<ClipEntity>> = dao.observeAll()

    suspend fun add(text: String) {
        if (text.isBlank()) return
        if (dao.exists(text) > 0) {
            // Already exists — just bump it to top
            dao.updateTimestamp(text)
        } else {
            dao.insert(ClipEntity(text = text))
            dao.evictBeyondCap()
        }
    }

    suspend fun delete(clip: ClipEntity) = dao.delete(clip)

    suspend fun clearAll() = dao.clearUnpinned()

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun getAll(): List<ClipEntity> = dao.getAll()

    companion object {
        @Volatile private var INSTANCE: ClipRepository? = null

        fun getInstance(context: Context): ClipRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipRepository(
                    ClipDatabase.getInstance(context).clipDao()
                ).also { INSTANCE = it }
            }
    }

    // In ClipRepository.kt
    suspend fun addIfNew(text: String): Boolean {
        if (text.isBlank()) return false
        if (dao.exists(text) > 0) {
            dao.updateTimestamp(text)
            return false
        }
        dao.insert(ClipEntity(text = text))
        dao.evictBeyondCap()
        return true
    }
}