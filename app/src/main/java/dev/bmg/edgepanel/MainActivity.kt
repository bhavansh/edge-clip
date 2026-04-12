// MainActivity.kt
package dev.bmg.edgepanel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bmg.edgepanel.clipboard.ClipboardAccessibilityService
import dev.bmg.edgepanel.service.EdgePanelService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Recheck permissions every time screen resumes
            var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
            var hasA11y by remember { mutableStateOf(isAccessibilityEnabled()) }

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
                    onStartClick = {
                        startService(Intent(this, EdgePanelService::class.java))
                    },
                    onStopClick = {
                        stopService(Intent(this, EdgePanelService::class.java))
                    }
                )
            }
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
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    var running by remember { mutableStateOf(false) }
    val allGranted = hasOverlayPermission && hasAccessibilityPermission

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
                            if (running) "Panel is active" else "Panel is stopped",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (running) "Swipe the edge handle to open"
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
                                if (running) Color(0xFF34C759) else Color(0xFFFF3B30)
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
                    onGrant = onRequestAccessibility
                )
            }

            // ── Action buttons ─────────────────────────────────────────
            OneUICard {
                if (!running) {
                    Button(
                        onClick = {
                            onStartClick()
                            running = true
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
                            running = false
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
    onGrant: () -> Unit
) {
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
}