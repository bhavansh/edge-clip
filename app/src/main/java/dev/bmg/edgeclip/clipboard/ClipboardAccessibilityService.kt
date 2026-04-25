package dev.bmg.edgeclip.clipboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.data.SettingsManager
import dev.bmg.edgeclip.service.EdgeClipService
import dev.bmg.edgeclip.service.ServiceState
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

class ClipboardAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private var repository: ClipRepository? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastStoredText: String? = null
    private var lastStoredImageUri: String? = null
    var isInternalCopy: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "=== SERVICE CONNECTED ===")
        serviceInfo = AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or 
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        
        Handler(Looper.getMainLooper()).postDelayed({ initClipboard() }, 300)
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ServiceState.isServiceRunning.value) return

        // Fullscreen detection
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkFullscreenState()
        }

        val cm = clipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0) ?: return
        val description = clip.description ?: return

        if ((0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }) {
            val uri = item.uri
            if (uri != null && uri.toString() != lastStoredImageUri) {
                handleImageClip(uri)
            }
            return
        }

        val text = try {
            item.coerceToText(this)?.toString()?.trim()
        } catch (e: Exception) { return }

        if (text.isNullOrBlank() || text == lastStoredText) return
        if (isInternalCopy) { isInternalCopy = false; lastStoredText = text; return }
        lastStoredText = text
        scope.launch(Dispatchers.IO) { repository?.add(text) }
    }
    private fun checkFullscreenState() {
        try {
            val windowList = windows
            if (windowList.isEmpty()) return

            var isFullscreenFound = false
            var isStatusBarVisible = false
            
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            for (window in windowList) {
                val rect = Rect()
                window.getBoundsInScreen(rect)

                // 1. Detect if a Status Bar exists and is at the top
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    if (rect.top == 0 && rect.width() >= screenWidth && rect.height() > 0 && rect.height() < screenHeight * 0.1) {
                        isStatusBarVisible = true
                    }
                }

                // 2. Check application windows
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    // Ignore our own app
                    val packageName = try { window.root?.packageName?.toString() } catch (e: Exception) { null }
                    if (packageName == packageName) {
                        // We can't easily compare packageName here without context, 
                        // but we can check if it's the focused window and if it's our app.
                        // For now, let's rely on the Status Bar detection which is more universal.
                    }

                    val w = rect.width()
                    val h = rect.height()
                    
                    // If an app window is exactly screen size, it's a candidate
                    if (w >= screenWidth && h >= screenHeight) {
                        isFullscreenFound = true
                    }
                }
            }

            // A window is only truly "Fullscreen Immersive" if it fills the screen 
            // AND the status bar is NOT visible/taking up space.
            val finalHideState = isFullscreenFound && !isStatusBarVisible
            
            EdgeClipService.instance?.setHandleForceHidden(finalHideState)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking fullscreen state", e)
        }
    }

    private fun handleImageClip(uri: Uri) {
        val uriString = uri.toString()
        if (isInternalCopy) {
            isInternalCopy = false
            lastStoredImageUri = uriString
            return
        }
        if (uriString == lastStoredImageUri) return  // ← skip if already stored
        lastStoredImageUri = uriString

        scope.launch(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                val extension = when {
                    mimeType.contains("png") -> "png"
                    mimeType.contains("gif") -> "gif"
                    mimeType.contains("webp") -> "webp"
                    else -> "jpg"
                }

                val bytes = if (extension == "gif") {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } else {
                    processImageUri(uri, extension)
                } ?: return@launch

                repository?.addImage(bytes, extension)
            } catch (e: Exception) {
                Log.e(TAG, "Image capture failed", e)
            }
        }
    }

    private fun processImageUri(uri: Uri, extension: String): ByteArray? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return null

            // Scale down if huge — panel is narrow, no need for full res
            val maxDim = 1200
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
            } else bitmap

            val format = when (extension) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSLESS
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> Bitmap.CompressFormat.JPEG
            }

            ByteArrayOutputStream().also { out ->
                scaled.compress(format, 82, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Process image failed", e)
            null
        }
    }
    override fun onInterrupt() {}

    // =========================================================================
    // Called when panel opens — guaranteed fresh read
    // =========================================================================

    fun readClipboardNow() {
        val cm = clipboardManager ?: return
        val clip = cm.primaryClip ?: return
        val item = clip.getItemAt(0) ?: return
        val description = clip.description

        if ((0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }) {
            val uri = item.uri
            if (uri != null && uri.toString() != lastStoredImageUri) {
                handleImageClip(uri)
            }
            return
        }

        val text = try {
            item.coerceToText(this)?.toString()?.trim()
        } catch (e: Exception) { return }

        if (text.isNullOrBlank() || text == lastStoredText) return
        if (isInternalCopy) { isInternalCopy = false; lastStoredText = text; return }
        lastStoredText = text
        scope.launch(Dispatchers.IO) { repository?.add(text) }
    }
    private fun initClipboard() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        repository = ClipRepository.getInstance(this)
        Log.d(TAG, "Init complete")
        readClipboardNow()
    }

    companion object {
        private const val TAG = "ClipboardA11y"
        var instance: ClipboardAccessibilityService? = null
    }

    init { instance = this }
}






