package dev.bmg.edgepanel.clipboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.bmg.edgepanel.data.ClipRepository
import kotlinx.coroutines.*

class ClipboardAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private var repository: ClipRepository? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The last text we successfully stored in Room.
    // Only updated after a successful store — so if the same
    // text appears again we skip it, but any new text is always caught.
    private var lastStoredText: String? = null

    // Polling runnable — runs every 500ms while service is alive.
    // This is the MIUI workaround: since the ClipboardManager listener
    // is silently suppressed, we poll instead.
    private val pollRunnable = object : Runnable {
        override fun run() {
            checkClipboard()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    var isInternalCopy: Boolean = false

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "=== SERVICE CONNECTED ===")

        serviceInfo = AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        mainHandler.postDelayed({
            initClipboard()
        }, 300)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        mainHandler.removeCallbacks(pollRunnable)
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // =========================================================================
    // Accessibility Event — secondary trigger on window changes
    // Fires immediately on app switches so we don't wait for next poll
    // =========================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            checkClipboard()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Interrupted")
    }

    // =========================================================================
    // Init
    // =========================================================================

    private fun initClipboard() {
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            repository = ClipRepository.getInstance(this)

            if (clipboardManager == null) {
                Log.e(TAG, "ClipboardManager is null!")
                return
            }

            Log.d(TAG, "Clipboard init complete — starting poll")

            // Start polling immediately
            mainHandler.post(pollRunnable)

        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
        }
    }

    // =========================================================================
    // Core check — called from both poll and accessibility events
    // Reads clipboard on Main thread (required on MIUI), stores if new
    // =========================================================================

    private fun checkClipboard() {
        scope.launch {
            try {
                val text = withContext(Dispatchers.Main) {
                    clipboardManager
                        ?.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(this@ClipboardAccessibilityService)
                        ?.toString()
                        ?.trim()
                }

                if (text.isNullOrBlank()) return@launch
                if (text == lastStoredText) return@launch

                if (isInternalCopy) {
                    isInternalCopy = false
                    lastStoredText = text  // update tracker but don't store
                    return@launch
                }

                lastStoredText = text
                repository?.add(text)
                Log.d(TAG, "Stored new clip: '${text.take(60)}'")

            } catch (e: Exception) {
                Log.e(TAG, "checkClipboard failed", e)
            }
        }
    }

    // =========================================================================
    // Singleton
    // =========================================================================

    companion object {
        private const val TAG = "ClipboardA11y"
        private const val POLL_INTERVAL_MS = 500L

        var instance: ClipboardAccessibilityService? = null
    }

    init { instance = this }
}