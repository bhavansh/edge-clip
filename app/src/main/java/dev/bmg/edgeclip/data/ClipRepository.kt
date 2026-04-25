package dev.bmg.edgeclip.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.security.MessageDigest

class ClipRepository private constructor(
    private val dao: ClipDao,
    private val context: Context
) {
    val clips: Flow<List<ClipEntity>> = dao.observeAll()
    private val settings = SettingsManager(context)

    // --- Text ---
    suspend fun add(text: String) {
        if (text.isBlank()) return
        if (dao.existsText(text) > 0) {
            dao.updateTimestamp(text)
        } else {
            dao.insert(ClipEntity(type = ClipType.TEXT, text = text))
            evictOldRecords()
        }
    }

    // --- Image ---
    suspend fun addImage(bytes: ByteArray, extension: String = "jpg"): String? {
        val hash = MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val fileName = "clip_img_$hash.$extension"
        val file = File(context.filesDir, fileName)

        return try {
            if (!file.exists()) {
                file.writeBytes(bytes)
            }
            val path = file.absolutePath
            if (dao.existsImage(path) > 0) {
                dao.updateTimestampImage(path)
            } else {
                dao.insert(ClipEntity(type = ClipType.IMAGE, imagePath = path))
                evictOldRecords()
            }
            path
        } catch (e: Exception) {
            Log.e("ClipRepository", "Failed to save image", e)
            null
        }
    }

    private suspend fun evictOldRecords() {
        val cap = settings.databaseLimit
        val paths = dao.evictedImagePaths(cap)
        paths.forEach { File(it).delete() }
        dao.evictBeyondCap(cap)
        
        // Also cleanup by time if enabled (retention is stored in HOURS)
        val hours = settings.retentionDays
        if (hours > 0) {
            val ms = hours.toLong() * 60 * 60 * 1000L
            val cutoff = System.currentTimeMillis() - ms
            val oldPaths = dao.getOldImagePaths(cutoff)
            oldPaths.forEach { File(it).delete() }
            dao.deleteOlderThan(cutoff)
        }
    }

    suspend fun pruneToLimit(newLimit: Int) {
        val paths = dao.evictedImagePaths(newLimit)
        paths.forEach { File(it).delete() }
        dao.evictBeyondCap(newLimit)
    }

    fun getDatabaseSize(): Long {
        var totalSize = 0L
        
        // 1. Database file size
        val dbFile = context.getDatabasePath("clip_history.db")
        if (dbFile.exists()) totalSize += dbFile.length()
        
        // 2. Images directory size
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("clip_img_")) {
                totalSize += file.length()
            }
        }
        
        return totalSize
    }

    data class StorageStats(
        val totalSize: Long,
        val totalCount: Int,
        val textCount: Int,
        val imageCount: Int
    )

    suspend fun getStorageStats(): StorageStats {
        return StorageStats(
            totalSize = getDatabaseSize(),
            totalCount = dao.getCount(),
            textCount = dao.getTextCount(),
            imageCount = dao.getImageCount()
        )
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