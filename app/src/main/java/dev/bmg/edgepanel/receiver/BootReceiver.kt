package dev.bmg.edgepanel.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",       // HTC/older Samsung
            "com.htc.intent.action.QUICKBOOT_POWERON",       // HTC devices
            Intent.ACTION_MY_PACKAGE_REPLACED -> {           // survives app updates
                Log.d(TAG, "Boot/update received: ${intent.action}")
                startEdgePanel(context)
            }
        }
    }

    private fun startEdgePanel(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission — skipping autostart")
            return
        }
        try {
            // Use startService, not startForegroundService —
            // EdgePanelService is an overlay service, not a foreground service
            val intent = Intent(context, dev.bmg.edgepanel.service.EdgePanelService::class.java)
            context.startService(intent)
            Log.d(TAG, "EdgePanelService started from boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start EdgePanelService", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}