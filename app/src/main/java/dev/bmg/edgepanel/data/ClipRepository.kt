package dev.bmg.edgepanel.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.File

class ClipRepository private constructor(
    private val dao: ClipDao,
    private val context: Context
) {
    val clips: Flow<List<ClipEntity>> = dao.observeAll()

    // --- Text ---
    suspend fun add(text: String) {
        if (text.isBlank()) return
        if (dao.existsText(text) > 0) {
            dao.updateTimestamp(text)
        } else {
            dao.insert(ClipEntity(type = ClipType.TEXT, text = text))
            dao.evictBeyondCap()
        }
    }

    // --- Image ---
    // Call this from the accessibility service with a compressed JPEG byte array.
    // Returns the saved file path, or null on failure.
    suspend fun addImage(jpegBytes: ByteArray): String? {
        val fileName = "clip_img_${System.currentTimeMillis()}.jpg"
        val file = File(context.filesDir, fileName)
        return try {
            file.writeBytes(jpegBytes)
            val path = file.absolutePath
            if (dao.existsImage(path) == 0) {
                dao.insert(ClipEntity(type = ClipType.IMAGE, imagePath = path))
                evictOldImages()
            }
            path
        } catch (e: Exception) {
            Log.e("ClipRepository", "Failed to save image", e)
            null
        }
    }

    private suspend fun evictOldImages(cap: Int = 50) {
        val paths = dao.evictedImagePaths(cap)
        paths.forEach { File(it).delete() }
        dao.evictBeyondCap(cap)
    }

    suspend fun delete(clip: ClipEntity) {
        clip.imagePath?.let { File(it).delete() }
        dao.delete(clip)
    }

    suspend fun clearAll() {
        val paths = dao.allUnpinnedImagePaths()
        paths.forEach { File(it).delete() }
        dao.clearUnpinned()
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)
    suspend fun getAll(): List<ClipEntity> = dao.getAll()

    companion object {
        @Volatile private var INSTANCE: ClipRepository? = null

        fun getInstance(context: Context): ClipRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipRepository(
                    ClipDatabase.getInstance(context).clipDao(),
                    context.applicationContext   // ← pass context
                ).also { INSTANCE = it }
            }
    }
}