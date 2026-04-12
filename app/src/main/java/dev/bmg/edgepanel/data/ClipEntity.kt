package dev.bmg.edgepanel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clip_history")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val copiedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)