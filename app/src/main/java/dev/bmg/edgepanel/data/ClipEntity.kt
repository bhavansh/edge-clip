// data/ClipEntity.kt
package dev.bmg.edgepanel.data

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "clip_history")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: ClipType = ClipType.TEXT,
    val text: String? = null,           // null for IMAGE entries
    val imagePath: String? = null,      // null for TEXT entries
    val copiedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)