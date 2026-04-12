package dev.bmg.edgepanel.clipboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle

class ClipboardProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra(EXTRA_TEXT)

        if (!text.isNullOrEmpty()) {
            // Tell accessibility service to skip the next clipboard event
            // so we don't re-add something the user already tapped "Copy" on
            ClipboardAccessibilityService.instance?.isInternalCopy = true

            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("edge_clip", text))
        }

        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_TEXT = "extra_text"
    }
}