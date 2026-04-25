package dev.bmg.edgeclip.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bmg.edgeclip.R
import dev.bmg.edgeclip.data.ClipEntity
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.data.ClipType
import dev.bmg.edgeclip.ui.theme.OtpDark
import dev.bmg.edgeclip.ui.theme.OtpLight
import dev.bmg.edgeclip.ui.theme.PhoneDark
import dev.bmg.edgeclip.ui.theme.PhoneLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(repository: ClipRepository, storageStats: ClipRepository.StorageStats?) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    
    val clips by (if (searchQuery.isBlank()) repository.clips else repository.search(searchQuery))
        .collectAsState(initial = emptyList())
    
    val filteredClips = remember(clips, selectedFilter) {
        when (selectedFilter) {
            "ALL" -> clips
            "IMAGE" -> clips.filter { it.type == ClipType.IMAGE }
            else -> clips.filter { it.subtype == selectedFilter }
        }
    }
    
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
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search history...") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(painterResource(R.drawable.ic_close), null, modifier = Modifier.size(20.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        )

        val filterTypes = listOf("ALL", "URL", "PHONE", "OTP", "IMAGE")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filterTypes.forEach { type ->
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = { selectedFilter = type },
                    label = { Text(type, fontSize = 12.sp) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        if (filteredClips.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isEmpty()) "No items found" else "No results for \"$searchQuery\"", 
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(filteredClips, key = { _, clip -> clip.id }) { index, clip ->
                    var showCopied by remember { mutableStateOf(false) }
                    
                    SwipeToDeleteContainer(
                        onDelete = { scope.launch { repository.delete(clip) } }
                    ) {
                        ClipItem(
                            clip = clip,
                            index = index + 1,
                            onDelete = { scope.launch { repository.delete(clip) } },
                            onCopy = {
                                copyToClipboard(context, clip)
                                showCopied = true
                                scope.launch {
                                    kotlinx.coroutines.delay(1500)
                                    showCopied = false
                                }
                            },
                            showCopied = showCopied
                        )
                    }
                }
                
                if (selectedFilter == "ALL") {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState()

    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            state.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Color(0xFFFF3B30).copy(alpha = 0.8f)
            } else Color.Transparent
            
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(painterResource(R.drawable.ic_close), null, tint = Color.White)
            }
        },
        content = { content() }
    )
}

private fun copyToClipboard(context: android.content.Context, clip: ClipEntity) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    dev.bmg.edgeclip.clipboard.ClipboardAccessibilityService.instance?.isInternalCopy = true
    
    if (clip.type == dev.bmg.edgeclip.data.ClipType.TEXT) {
        cm.setPrimaryClip(android.content.ClipData.newPlainText("clip", clip.text))
    } else {
        clip.imagePath?.let { path ->
            val file = File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "image", uri))
        }
    }
}

@Composable
fun ClipItem(clip: ClipEntity, index: Int, onDelete: () -> Unit, onCopy: () -> Unit, showCopied: Boolean) {
    val context = LocalContext.current
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
                        val isDark = isSystemInDarkTheme()
                        val otpColor = if (isDark) OtpDark else OtpLight
                        val phoneColor = if (isDark) PhoneDark else PhoneLight
                        
                        val annotatedText = buildAnnotatedString {
                            val text = clip.text ?: ""
                            append(text)
                            
                            when (clip.subtype) {
                                "OTP" -> {
                                    val regex = Regex("(?<![\\d.])\\d{4,8}(?![\\d.])")
                                    regex.find(text)?.let { match ->
                                        addStyle(
                                            style = SpanStyle(color = otpColor, fontWeight = FontWeight.Bold),
                                            start = match.range.first,
                                            end = match.range.last + 1
                                        )
                                    }
                                }
                                "PHONE" -> {
                                    val regex = Regex("(?:\\+?\\d{1,3}[- ]?)?\\d{3,5}[- ]?\\d{3,5}(?:[- ]?\\d{1,5})?")
                                    regex.find(text)?.let { match ->
                                        addStyle(
                                            style = SpanStyle(color = phoneColor, fontWeight = FontWeight.Bold),
                                            start = match.range.first,
                                            end = match.range.last + 1
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = annotatedText,
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
                // 1. Contextual Action Icon
                if (clip.subtype != "NONE") {
                    IconButton(
                        onClick = { 
                            val text = clip.text ?: ""
                            try {
                                val intent = when (clip.subtype) {
                                    "URL" -> android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(text))
                                    "PHONE" -> android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$text"))
                                    "OTP" -> {
                                        val digits = Regex("\\d{4,8}").find(text)?.value ?: text
                                        copyToClipboard(context, clip.copy(text = digits))
                                        android.widget.Toast.makeText(context, "OTP Copied", android.widget.Toast.LENGTH_SHORT).show()
                                        null
                                    }
                                    else -> null
                                }
                                intent?.let {
                                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(it)
                                }
                            } catch (e: Exception) {}
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        val iconRes = when (clip.subtype) {
                            "URL" -> R.drawable.ic_link
                            "PHONE" -> R.drawable.ic_call
                            "OTP" -> R.drawable.ic_key
                            else -> 0
                        }
                        if (iconRes != 0) {
                            val isDark = isSystemInDarkTheme()
                            val tint = when (clip.subtype) {
                                "OTP" -> if (isDark) OtpDark else OtpLight
                                "PHONE" -> if (isDark) PhoneDark else PhoneLight
                                else -> MaterialTheme.colorScheme.secondary
                            }
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = "Action",
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)))
                }

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
