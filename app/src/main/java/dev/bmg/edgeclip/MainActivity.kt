// MainActivity.kt
package dev.bmg.edgeclip

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bmg.edgeclip.clipboard.ClipboardAccessibilityService
import dev.bmg.edgeclip.data.SettingsManager
import dev.bmg.edgeclip.service.EdgeClipService
import dev.bmg.edgeclip.service.ServiceState
import dev.bmg.edgeclip.ui.theme.EdgeClipTheme

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.ui.HistoryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager(this)
        val repository = ClipRepository.getInstance(this)

        setContent {
            val context = LocalContext.current
            val isRunning by ServiceState.isServiceRunning.collectAsStateWithLifecycle()
            
            var selectedTab by remember { mutableIntStateOf(0) }

            // Settings State
            var bgPollingEnabled by remember { mutableStateOf(settingsManager.isBackgroundPollingEnabled) }
            var pollingFreq by remember { mutableStateOf(settingsManager.pollingFrequencySeconds.toFloat()) }
            var edgeSide by remember { mutableStateOf(settingsManager.edgeSide) }
            
            // New Settings
            var dbLimit by remember { mutableIntStateOf(settingsManager.databaseLimit) }
            var retentionDays by remember { mutableIntStateOf(settingsManager.retentionDays) }
            var closeOutside by remember { mutableStateOf(settingsManager.closeOnOutsideClick) }
            var storageStats by remember { mutableStateOf<ClipRepository.StorageStats?>(null) }
            
            val scope = rememberCoroutineScope()
            
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val stats = repository.getStorageStats()
                    storageStats = stats
                    if (stats.totalCount == 0) {
                        selectedTab = 1
                    }
                }
            }

            // Recheck permissions every time screen resumes
            var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
            var hasA11y by remember { mutableStateOf(isAccessibilityEnabled()) }
            var hasNotificationPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasNotificationPermission = isGranted
            }

            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        hasOverlay = Settings.canDrawOverlays(this@MainActivity)
                        hasA11y = isAccessibilityEnabled()
                        scope.launch(Dispatchers.IO) {
                            storageStats = repository.getStorageStats()
                        }
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            EdgeClipTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(painterResource(R.drawable.ic_history), null, modifier = Modifier.size(24.dp)) },
                                label = { Text("History") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(painterResource(R.drawable.ic_settings), null, modifier = Modifier.size(24.dp)) },
                                label = { Text("Settings") }
                            )
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        if (selectedTab == 0) {
                            HistoryScreen(repository, storageStats)
                        } else {
                            SettingsScreen(
                                hasOverlayPermission = hasOverlay,
                                hasAccessibilityPermission = hasA11y,
                                hasNotificationPermission = hasNotificationPermission,
                                bgPollingEnabled = bgPollingEnabled,
                                pollingFreq = pollingFreq,
                                edgeSide = edgeSide,
                                dbLimit = dbLimit,
                                retentionDays = retentionDays,
                                closeOutside = closeOutside,
                                storageStats = storageStats,
                                onBgPollingToggle = {
                                    bgPollingEnabled = it
                                    settingsManager.isBackgroundPollingEnabled = it
                                },
                                onPollingFreqChange = {
                                    pollingFreq = it.toFloat()
                                    settingsManager.pollingFrequencySeconds = it
                                },
                                onEdgeSideChange = {
                                    edgeSide = it
                                    settingsManager.edgeSide = it
                                },
                                onDbLimitChange = { newLimit ->
                                    if (newLimit < dbLimit) {
                                        scope.launch(Dispatchers.IO) {
                                            repository.pruneToLimit(newLimit)
                                            storageStats = repository.getStorageStats()
                                        }
                                    }
                                    dbLimit = newLimit
                                    settingsManager.databaseLimit = newLimit
                                },
                                onRetentionDaysChange = {
                                    retentionDays = it
                                    settingsManager.retentionDays = it
                                },
                                onCloseOutsideToggle = {
                                    closeOutside = it
                                    settingsManager.closeOnOutsideClick = it
                                },
                                onRequestOverlay = {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                                },
                                onRequestAccessibility = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onRequestNotification = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onStartClick = {
                                    val intent = Intent(this@MainActivity, EdgeClipService::class.java)
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onStopClick = {
                                    stopService(Intent(this@MainActivity, EdgeClipService::class.java))
                                },
                                isRunning = isRunning
                            )
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            window.setHideOverlayWindows(true)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedComponent = ComponentName(
            this,
            ClipboardAccessibilityService::class.java
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }
}

@Composable
fun SettingsScreen(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasNotificationPermission: Boolean,
    bgPollingEnabled: Boolean,
    pollingFreq: Float,
    edgeSide: String,
    dbLimit: Int,
    retentionDays: Int,
    closeOutside: Boolean,
    storageStats: ClipRepository.StorageStats?,
    onBgPollingToggle: (Boolean) -> Unit,
    onPollingFreqChange: (Int) -> Unit,
    onEdgeSideChange: (String) -> Unit,
    onDbLimitChange: (Int) -> Unit,
    onRetentionDaysChange: (Int) -> Unit,
    onCloseOutsideToggle: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    isRunning: Boolean
) {
    val allGranted = hasOverlayPermission && hasAccessibilityPermission && hasNotificationPermission
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingLimit by remember { mutableIntStateOf(dbLimit) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────
            Text(
                "EdgeClip Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // ── Status card ────────────────────────────────────────────
            OneUICard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isRunning) "Service is active" else "Service is stopped",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (isRunning) "The edge handle is visible on the side"
                            else "Tap Start to activate the overlay",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            lineHeight = 18.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isRunning) Color(0xFF34C759) else Color(0xFFFF3B30)
                            )
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                if (!isRunning) {
                    Button(
                        onClick = onStartClick,
                        enabled = allGranted,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (allGranted) "Start Service" else "Grant Permissions First")
                    }
                } else {
                    Button(
                        onClick = onStopClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))
                    ) {
                        Text("Stop Service")
                    }
                }
            }

            // ── Permissions card ───────────────────────────────────────
            OneUICard {
                Text(
                    "Permissions",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                PermissionRow(label = "Overlay Permission", granted = hasOverlayPermission, onGrant = onRequestOverlay)
                PermissionRow(label = "Accessibility Service", granted = hasAccessibilityPermission, onGrant = onRequestAccessibility, showHint = Build.VERSION.SDK_INT >= 33 && !hasAccessibilityPermission)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(label = "Notifications", granted = hasNotificationPermission, onGrant = onRequestNotification)
                }
            }

            // ── Overlay Config ─────────────────────────────────────────
            OneUICard {
                Text("Overlay Configuration", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                
                Text("Edge Side", fontSize = 14.sp)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("left", "right").forEach { side ->
                        val isSelected = edgeSide == side
                        Button(
                            onClick = { onEdgeSideChange(side) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(side.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Close on outside click", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = closeOutside, onCheckedChange = onCloseOutsideToggle)
                }
                Text("Automatically hide the panel when you tap outside it.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }

            // ── Database & History ──────────────────────────────────────
            OneUICard {
                Text("Database & History", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                
                storageStats?.let { stats ->
                    val sizeMb = String.format(Locale.US, "%.2f MB", stats.totalSize / (1024f * 1024f))
                    Text("Storage Size: $sizeMb", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Text("Total Records: ${stats.totalCount} (${stats.textCount} Text, ${stats.imageCount} Image)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                
                Spacer(Modifier.height(12.dp))
                
                Text("Maximum History Items", fontSize = 14.sp)
                val limits = listOf(10, 50, 100, 200, 500)
                SettingsDropdown(
                    currentValue = dbLimit,
                    options = limits,
                    onValueChange = { 
                        if (it < dbLimit) {
                            pendingLimit = it
                            showDeleteConfirm = true
                        } else {
                            onDbLimitChange(it)
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))
                
                Text("Retention Period", fontSize = 14.sp)
                val retentionOptions = listOf(0, 1, 6, 24, 168, 720, 2160) // Hours
                val retentionLabels = mapOf(
                    0 to "Never", 
                    1 to "1 Hour", 
                    6 to "6 Hours", 
                    24 to "1 Day", 
                    168 to "7 Days", 
                    720 to "30 Days", 
                    2160 to "90 Days"
                )
                SettingsDropdown(
                    currentValue = retentionDays,
                    options = retentionOptions,
                    labelMap = retentionLabels,
                    onValueChange = onRetentionDaysChange
                )
            }
            
            // ── Polling Config ─────────────────────────────────────────
            OneUICard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Background Polling", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = bgPollingEnabled, onCheckedChange = onBgPollingToggle)
                }
                if (bgPollingEnabled) {
                    Text("Frequency", fontSize = 14.sp)
                    val freqOptions = (5..60 step 5).toList()
                    SettingsDropdown(
                        currentValue = pollingFreq.toInt(),
                        options = freqOptions,
                        onValueChange = onPollingFreqChange,
                        suffix = "s"
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Warning: Background polling may miss items if multiple copies occur within a time window shorter than the polling frequency.", 
                        fontSize = 11.sp, color = Color(0xFFFF3B30).copy(alpha = 0.8f), lineHeight = 14.sp)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Reduce History Limit?") },
            text = { Text("Lowering the limit to $pendingLimit will immediately delete older clipboard items that exceed this new capacity. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDbLimitChange(pendingLimit)
                    showDeleteConfirm = false
                }) { Text("Confirm Deletion", color = Color(0xFFFF3B30)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsDropdown(
    currentValue: T,
    options: List<T>,
    labelMap: Map<T, String>? = null,
    onValueChange: (T) -> Unit,
    suffix: String = ""
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = labelMap?.get(currentValue) ?: "$currentValue$suffix"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                val label = labelMap?.get(option) ?: "$option$suffix"
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun OneUICard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.5.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onGrant: () -> Unit,
    showHint: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (granted) {
                Text(
                    "Granted",
                    fontSize = 13.sp,
                    color = Color(0xFF34C759),
                    fontWeight = FontWeight.Medium
                )
            } else {
                TextButton(
                    onClick = onGrant,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "Grant →",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (showHint) {
            Text(
                "Note: If grayed out, go to App Info > (⋮) > Allow restricted settings.",
                fontSize = 11.sp,
                color = Color(0xFFFF3B30),
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
