package dev.bmg.edgeclip.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bmg.edgeclip.R
import dev.bmg.edgeclip.data.ClipEntity
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.data.ClipType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(repository: ClipRepository, storageStats: ClipRepository.StorageStats?) {
    val clips by repository.clips.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Clipboard History",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp)
        )
        
        storageStats?.let { stats ->
            val sizeMb = String.format(Locale.US, "%.2f MB", stats.totalSize / (1024f * 1024f))
            Text(
                text = "$sizeMb  ·  ${stats.totalCount} items",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (clips.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No items found", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                itemsIndexed(clips, key = { _, clip -> clip.id }) { index, clip ->
                    var showCopied by remember { mutableStateOf(false) }
                    
                    ClipItem(
                        clip = clip,
                        index = index + 1,
                        onDelete = { scope.launch { repository.delete(clip) } },
                        onCopy = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            dev.bmg.edgeclip.clipboard.ClipboardAccessibilityService.instance?.isInternalCopy = true
                            
                            if (clip.type == ClipType.TEXT) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("clip", clip.text))
                            } else {
                                clip.imagePath?.let { path ->
                                    val file = File(path)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "image", uri))
                                }
                            }
                            
                            showCopied = true
                            scope.launch {
                                kotlinx.coroutines.delay(1500)
                                showCopied = false
                            }
                        },
                        showCopied = showCopied
                    )
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { scope.launch { repository.clearAll() } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Clear All")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun ClipItem(clip: ClipEntity, index: Int, onDelete: () -> Unit, onCopy: () -> Unit, showCopied: Boolean) {
    val timestamp = remember(clip.copiedAt) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(clip.copiedAt))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#$index",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Text(
                    text = timestamp,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                when (clip.type) {
                    ClipType.TEXT -> {
                        Text(
                            text = clip.text ?: "",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    ClipType.IMAGE -> {
                        val bitmap = remember(clip.imagePath) {
                            mutableStateOf<android.graphics.Bitmap?>(null)
                        }
                        LaunchedEffect(clip.imagePath) {
                            withContext(Dispatchers.IO) {
                                try {
                                    bitmap.value = BitmapFactory.decodeFile(clip.imagePath)
                                } catch (e: Exception) { }
                            }
                        }
                        bitmap.value?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .padding(bottom = 12.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(if (showCopied) R.drawable.ic_check else R.drawable.ic_copy),
                        contentDescription = "Copy",
                        tint = if (showCopied) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)))

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Delete",
                        tint = Color(0xFFFF3B30).copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
