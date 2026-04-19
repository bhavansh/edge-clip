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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager(this)

        setContent {
            val context = LocalContext.current
            val isRunning by ServiceState.isServiceRunning.collectAsStateWithLifecycle()

            // Settings State
            var bgPollingEnabled by remember { mutableStateOf(settingsManager.isBackgroundPollingEnabled) }
            var pollingFreq by remember { mutableStateOf(settingsManager.pollingFrequencySeconds.toFloat()) }

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

            // Update state when activity resumes (user returning from Settings)
            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        hasOverlay = Settings.canDrawOverlays(this@MainActivity)
                        hasA11y = isAccessibilityEnabled()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            EdgeClipTheme {
                EdgeClipScreen(
                    hasOverlayPermission     = hasOverlay,
                    hasAccessibilityPermission = hasA11y,
                    hasNotificationPermission = hasNotificationPermission,
                    bgPollingEnabled = bgPollingEnabled,
                    pollingFreq = pollingFreq,
                    onBgPollingToggle = {
                        bgPollingEnabled = it
                        settingsManager.isBackgroundPollingEnabled = it
                    },
                    onPollingFreqChange = {
                        pollingFreq = it
                        settingsManager.pollingFrequencySeconds = it.toInt()
                    },
                    onRequestOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
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
                        val intent = Intent(this, EdgeClipService::class.java)
                        ContextCompat.startForegroundService(this, intent)
                    },
                    onStopClick = {
                        stopService(Intent(this, EdgeClipService::class.java))
                    },
                    isRunning = isRunning
                )
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
fun EdgeClipScreen(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasNotificationPermission: Boolean,
    bgPollingEnabled: Boolean,
    pollingFreq: Float,
    onBgPollingToggle: (Boolean) -> Unit,
    onPollingFreqChange: (Float) -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotification: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    isRunning: Boolean
) {
    val allGranted = hasOverlayPermission && hasAccessibilityPermission && hasNotificationPermission

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 56.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────
            Text(
                "EdgeClip",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "v1.0  ·  by Bhavansh",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.offset(y = (-8).dp)
            )

            Spacer(Modifier.height(8.dp))

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
                            if (isRunning) "Swipe the edge handle to open"
                            else "Tap Start to activate the edge clip",
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
                PermissionRow(
                    label = "Display over other apps",
                    granted = hasOverlayPermission,
                    onGrant = onRequestOverlay
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    label = "Accessibility (clipboard access)",
                    granted = hasAccessibilityPermission,
                    onGrant = onRequestAccessibility,
                    showHint = Build.VERSION.SDK_INT >= 33 && !hasAccessibilityPermission
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        label = "Notifications (keep service alive)",
                        granted = hasNotificationPermission,
                        onGrant = onRequestNotification
                    )
                }
            }

            // ── Settings Card ─────────────────────────────────────────
            OneUICard {
                Text(
                    "Configuration",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Background Polling",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = bgPollingEnabled,
                        onCheckedChange = onBgPollingToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFD1D1D6)
                        )
                    )
                }
                
                if (bgPollingEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Polling Frequency: ${pollingFreq.toInt()}s",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = pollingFreq,
                        onValueChange = onPollingFreqChange,
                        valueRange = 2f..60f,
                        steps = 58,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                        )
                    )
                    Text(
                        "Interval between clipboard checks in seconds.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                } else {
                    Text(
                        "Manual Mode: Clipboard will only be checked when you open the panel.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        lineHeight = 14.sp
                    )
                }
            }

            // ── Action buttons ─────────────────────────────────────────
            OneUICard {
                if (!isRunning) {
                    Button(
                        onClick = {
                            onStartClick()
                        },
                        enabled = allGranted,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = Color(0xFFD1D1D6)
                        )
                    ) {
                        Text(
                            if (allGranted) "Start EdgeClip"
                            else "Grant permissions first",
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            onStopClick()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF3B30)
                        )
                    ) {
                        Text("Stop EdgeClip", fontSize = 15.sp, color = Color.White)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Footer ─────────────────────────────────────────────────
            Text(
                "Clipboard data is stored locally and encrypted. No data leaves your device.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                lineHeight = 16.sp
            )
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
