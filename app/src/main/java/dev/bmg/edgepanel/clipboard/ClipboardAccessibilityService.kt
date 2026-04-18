package dev.bmg.edgepanel.clipboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.bmg.edgepanel.data.ClipRepository
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

class ClipboardAccessibilityService : AccessibilityService() {

    private var clipboardManager: ClipboardManager? = null
    private var repository: ClipRepository? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastStoredText: String? = null
    private var lastStoredImageUri: String? = null  // ← add this
    var isInternalCopy: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "=== SERVICE CONNECTED ===")
        serviceInfo = AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Handler(Looper.getMainLooper()).postDelayed({ initClipboard() }, 300)
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // =========================================================================
    // onAccessibilityEvent — read clipboard SYNCHRONOUSLY here.
    // This runs on the main thread mid-event, giving us the brief
    // foreground context MIUI requires. No coroutine dispatch — read now.
    // =========================================================================


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
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






