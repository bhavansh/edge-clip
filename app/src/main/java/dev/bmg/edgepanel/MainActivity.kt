// MainActivity.kt
package dev.bmg.edgepanel

import android.Manifest
import android.content.ComponentName
import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.bmg.edgepanel.clipboard.ClipboardAccessibilityService
import dev.bmg.edgepanel.service.EdgePanelService
import dev.bmg.edgepanel.service.ServiceState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val isRunning by ServiceState.isServiceRunning.collectAsStateWithLifecycle()

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

            MaterialTheme(
                colorScheme = lightColorScheme(
                    background   = Color(0xFFF2F2F7),
                    surface      = Color(0xFFFFFFFF),
                    primary      = Color(0xFF007AFF),
                    onPrimary    = Color.White,
                    onBackground = Color(0xFF1C1C1E),
                    onSurface    = Color(0xFF1C1C1E),
                )
            ) {
                EdgePanelScreen(
                    hasOverlayPermission     = hasOverlay,
                    hasAccessibilityPermission = hasA11y,
                    hasNotificationPermission = hasNotificationPermission,
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
                        val intent = Intent(this, EdgePanelService::class.java)
                        ContextCompat.startForegroundService(this, intent)
                    },
                    onStopClick = {
                        stopService(Intent(this, EdgePanelService::class.java))
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
fun EdgePanelScreen(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasNotificationPermission: Boolean,
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
                .padding(horizontal = 20.dp, vertical = 56.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────
            Text(
                "Edge Panel",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "v1.0  ·  by Bhavansh",
                fontSize = 13.sp,
                color = Color(0xFF8E8E93),
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
                            if (isRunning) "Panel is active" else "Panel is stopped",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (isRunning) "Swipe the edge handle to open"
                            else "Tap Start to activate the edge panel",
                            fontSize = 13.sp,
                            color = Color(0xFF8E8E93),
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
                            containerColor = Color(0xFF007AFF),
                            disabledContainerColor = Color(0xFFD1D1D6)
                        )
                    ) {
                        Text(
                            if (allGranted) "Start Edge Panel"
                            else "Grant permissions first",
                            fontSize = 15.sp
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
                        Text("Stop Edge Panel", fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Footer ─────────────────────────────────────────────────
            Text(
                "Clipboard data is stored locally and encrypted. No data leaves your device.",
                fontSize = 12.sp,
                color = Color(0xFFAEAEB2),
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
        tonalElevation = 0.dp,
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
                color = Color(0xFF3C3C43),
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
                        color = Color(0xFF007AFF),
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
