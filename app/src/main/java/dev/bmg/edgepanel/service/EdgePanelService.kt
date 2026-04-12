
// service/EdgePanelService.kt
package dev.bmg.edgepanel.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import dev.bmg.edgepanel.clipboard.ClipboardAccessibilityService
import dev.bmg.edgepanel.data.ClipEntity
import dev.bmg.edgepanel.data.ClipRepository
import dev.bmg.edgepanel.data.ClipType
import dev.bmg.edgepanel.view.GestureScrollView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import kotlin.math.abs

class EdgePanelService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var edgeView: View
    private var panelView: View? = null

    private lateinit var repository: ClipRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var contentContainer: LinearLayout? = null
    private var flowJob: Job? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    // Replace your onCreate wiring with this
    override fun onCreate() {
        super.onCreate()
        repository = ClipRepository.getInstance(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createEdgeHandle()
        createFocusWindow()          // create invisible focus window
        wireAccessibilityService()   // wire callback when service is ready
        Log.d(TAG, "Service created")
    }

// =========================================================================
// Focus window — only used to get MIUI clipboard access.
// FLAG_ALT_FOCUSABLE_IM is the key: we can be focusable without
// stealing keyboard (IME) focus or intercepting back gestures.
// =========================================================================

    private var focusWindow: View? = null
    private var focusWindowParams: WindowManager.LayoutParams? = null
    private var clipboardPollHandler: Handler? = null
    private val POLL_INTERVAL_MS = 5000L

    private val idleFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private val readFlags =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or   // ← IME keeps priority
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun createFocusWindow() {
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            idleFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0; y = 0
            alpha = 0f
        }
        val view = View(this)
        focusWindow = view
        focusWindowParams = params
        windowManager.addView(view, params)

        startPeriodicClipboardPoll()
    }

    private fun startPeriodicClipboardPoll() {
        val handler = Handler(Looper.getMainLooper())
        clipboardPollHandler = handler

        val pollRunnable = object : Runnable {
            override fun run() {
                doFocusRead()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        // First read after a short warm-up delay
        handler.postDelayed(pollRunnable, 1500)
    }

    private fun stopPeriodicClipboardPoll() {
        clipboardPollHandler?.removeCallbacksAndMessages(null)
        clipboardPollHandler = null
    }

    fun triggerFocusRead() {
        // Called on panel open — immediate read, no delay needed
        doFocusRead()
    }

    private fun doFocusRead() {
        val fw = focusWindow ?: return
        val params = focusWindowParams ?: return

        // Step 1: Switch to focusable-but-IME-safe flags
        params.flags = readFlags
        try {
            windowManager.updateViewLayout(fw, params)
        } catch (e: Exception) {
            return
        }

        // Step 2: Read clipboard
        ClipboardAccessibilityService.instance?.readClipboardNow()

        // Step 3: Restore idle flags after a minimal delay (60ms is enough)
        Handler(Looper.getMainLooper()).postDelayed({
            params.flags = idleFlags
            try { windowManager.updateViewLayout(fw, params) } catch (_: Exception) {}
        }, 60)
    }

    private fun removeFocusWindow() {
        stopPeriodicClipboardPoll()
        focusWindow?.let { safeRemoveView(it) }
        focusWindow = null
        focusWindowParams = null
    }

    override fun onDestroy() {
        scope.cancel()
        removeFocusWindow()
        panelView?.let { safeRemoveView(it) }
        if (::edgeView.isInitialized) safeRemoveView(edgeView)
        super.onDestroy()
    }

    // Retries every 500ms until accessibility service is available
    private fun wireAccessibilityService() {
        val handler = Handler(Looper.getMainLooper())
        val attempt = object : Runnable {
            override fun run() {
                val svc = ClipboardAccessibilityService.instance
                if (svc != null) {
                    Log.d(TAG, "Accessibility service wired successfully")
                } else {
                    Log.d(TAG, "Accessibility service not ready yet, retrying...")
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(attempt)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    // Edge Handle
    // =========================================================================

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun createEdgeHandle() {
        val params = handleLayoutParams()
        edgeView = View(this).apply {
            background = resources.getDrawable(
                dev.bmg.edgepanel.R.drawable.edge_handler_bg, theme
            )
        }
        windowManager.addView(edgeView, params)
        attachHandleTouchListener(params)
    }

    private fun handleLayoutParams() = WindowManager.LayoutParams(
        dpToPx(6), dpToPx(56),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).also { it.gravity = Gravity.END or Gravity.CENTER_VERTICAL }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouchListener(params: WindowManager.LayoutParams) {
        var initialY = 0
        var initialTouchY = 0f
        var isClick = true

        edgeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dy) > dpToPx(10)) {
                        isClick = false
                        params.y = initialY + dy
                        windowManager.updateViewLayout(edgeView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) togglePanel()
                    true
                }
                else -> false
            }
        }
    }

    // =========================================================================
    // Panel Toggle
    // =========================================================================

    private fun togglePanel() {
        if (panelView == null) openPanel() else closePanel()
    }

    // =========================================================================
    // Open Panel
    // =========================================================================

    private fun openPanel() {
        if (panelView != null) return

        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.40).toInt()
        val panelHeight = (resources.displayMetrics.heightPixels * 0.82).toInt()

        val params = WindowManager.LayoutParams(
            panelWidth, panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.END or Gravity.CENTER_VERTICAL }

        val panel = buildPanelRoot(panelWidth).also { root ->
            root.addView(buildScrollableContent())
            root.addView(buildClearButton())
        }

        panelView = panel
        windowManager.addView(panel, params)
        animatePanelIn(panel, panelWidth)
        edgeView.visibility = View.GONE
// Re-wire in case accessibility service restarted since onCreate
        // Read clipboard now — panel window focus gives us real MIUI clipboard access
        ClipboardAccessibilityService.instance?.readClipboardNow()
        // Start observing Room — updates UI whenever DB changes
        observeClips()
    }

    // =========================================================================
    // Observe Room via Flow
    // =========================================================================

    private fun observeClips() {
        flowJob?.cancel()
        flowJob = scope.launch {
            repository.clips.collectLatest { clips ->
                refreshPanel(clips)
            }
        }
    }

    // =========================================================================
    // Panel Root
    // =========================================================================

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun buildPanelRoot(panelWidth: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dpToPx(20), 0, dpToPx(12))
        background = resources.getDrawable(dev.bmg.edgepanel.R.drawable.panel_bg, theme)
        translationX = panelWidth.toFloat()
        elevation = dpToPx(12).toFloat()
    }

    // =========================================================================
    // Scrollable Content
    // =========================================================================

    private fun buildScrollableContent(): GestureScrollView {
        val scrollView = GestureScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            onFlingRight = { closePanel() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentContainer = container
        scrollView.addView(container)
        return scrollView
    }

    // =========================================================================
    // Refresh Panel — called every time Room emits a new list
    // =========================================================================

    private fun refreshPanel(clips: List<ClipEntity>) {
        val container = contentContainer ?: return

        container.removeAllViews()

        if (clips.isEmpty()) {
            container.addView(buildEmptyState())
            return
        }

        clips.forEach { clip ->
            container.addView(buildClipBlock(clip))
            container.addView(buildBlockDivider())
        }
    }

    // =========================================================================
    // Empty State
    // =========================================================================

    private fun buildEmptyState(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        addView(TextView(context).apply {
            text = "No items"
            textSize = 16f
            setTextColor(Color.parseColor("#1C1C1E"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Copied text will appear here"
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(6), 0, 0)
        })
    }

    // =========================================================================
    // Clip Block
    // =========================================================================

    private fun buildClipBlock(clip: ClipEntity): FrameLayout = FrameLayout(this).apply {
        setPadding(dpToPx(14), dpToPx(12), dpToPx(10), dpToPx(10))

        when (clip.type) {
            ClipType.TEXT -> {
                addView(TextView(context).apply {
                    text = clip.text
                    textSize = 13f
                    setTextColor(Color.parseColor("#1C1C1E"))
                    setLineSpacing(0f, 1.35f)
                    setPadding(0, 0, 0, dpToPx(26))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                })
                addView(buildPillRow(clip))
            }

            ClipType.IMAGE -> {
                val path = clip.imagePath
                if (path != null) {
                    addView(buildImageView(path))
                }
                addView(buildImagePillRow(clip))
            }
        }
    }

    private fun buildImageView(path: String): ImageView = ImageView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dpToPx(140)
        ).also { it.bottomMargin = dpToPx(30) }
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpToPx(8).toFloat())
            }
        }

        // Load off main thread
        scope.launch(Dispatchers.IO) {
            val bmp = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            if (bmp != null) {
                withContext(Dispatchers.Main) { setImageBitmap(bmp) }
            }
        }
    }

    private fun buildImagePillRow(clip: ClipEntity): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        )

        addView(buildCopyImagePill(clip))
        addView(Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(6), 1)
        })
        addView(buildDeletePill(clip))
    }

    private fun buildCopyImagePill(clip: ClipEntity): TextView = TextView(this).apply {
        text = "Copy"
        textSize = 11.5f
        setTextColor(Color.parseColor("#48484A"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#E8E8ED"))
        setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))

        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val path = clip.imagePath ?: return@setOnClickListener
            scope.launch(Dispatchers.IO) {
                try {
                    val file = File(path)
                    // Use FileProvider to expose the file, then set on clipboard
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@EdgePanelService,
                        "${packageName}.fileprovider",
                        file
                    )
                    val clipData = android.content.ClipData.newUri(
                        contentResolver, "image", uri
                    )
                    withContext(Dispatchers.Main) {
                        ClipboardAccessibilityService.instance?.isInternalCopy = true
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(clipData)
                        text = "Copied ✓"
                        setTextColor(Color.parseColor("#34C759"))
                        background = pill(Color.parseColor("#E3F9E8"))
                        postDelayed({
                            text = "Copy"
                            setTextColor(Color.parseColor("#48484A"))
                            background = pill(Color.parseColor("#E8E8ED"))
                        }, 1800)
                    }
                } catch (e: Exception) {
                    Log.e("EdgePanelService", "Image copy failed", e)
                }
            }
        }
    }

    // =========================================================================
    // Pill Row  (Copy + Delete side by side)
    // =========================================================================

    private fun buildPillRow(clip: ClipEntity): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        )

        // Copy pill
        addView(buildCopyPill(clip.text!!))

        // Small spacer
        addView(Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(6), 1)
        })

        // Delete pill
        addView(buildDeletePill(clip))
    }

    private fun buildCopyPill(clipText: String?): TextView = TextView(this).apply {
        text = "Copy"
        textSize = 11.5f
        setTextColor(Color.parseColor("#48484A"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#E8E8ED"))
        setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))

        setOnClickListener {
            copyToClipboard(clipText ?: return@setOnClickListener)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            text = "Copied ✓"
            setTextColor(Color.parseColor("#34C759"))
            background = pill(Color.parseColor("#E3F9E8"))
            postDelayed({
                text = "Copy"
                setTextColor(Color.parseColor("#48484A"))
                background = pill(Color.parseColor("#E8E8ED"))
            }, 1800)
        }
    }

    private fun buildDeletePill(clip: ClipEntity): TextView = TextView(this).apply {
        text = "✕"
        textSize = 11.5f
        setTextColor(Color.parseColor("#FF3B30"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#FFEEED"))
        setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))

        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            scope.launch(Dispatchers.IO) {
                repository.delete(clip)
            }
        }
    }

    // =========================================================================
    // Divider
    // =========================================================================

    private fun buildBlockDivider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#F2F2F7"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    // =========================================================================
    // Clear Button
    // =========================================================================

    private fun buildClearButton(): TextView = TextView(this).apply {
        text = "Clear All"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#636366"))
        background = pill(Color.parseColor("#EBEBF0"))
        setPadding(dpToPx(24), dpToPx(7), dpToPx(24), dpToPx(7))

        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = dpToPx(10)
        }

        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            scope.launch(Dispatchers.IO) {
                repository.clearAll()
            }
            text = "Cleared"
            postDelayed({ text = "Clear All" }, 1500)
        }
    }

    // =========================================================================
    // Close Panel
    // =========================================================================

    private fun closePanel() {
        flowJob?.cancel()
        flowJob = null

        panelView?.let { panel ->
            if (panel.tag == "closing") return
            panel.tag = "closing"

            val slideOut = resources.displayMetrics.widthPixels * 0.40f
            panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            panel.animate()
                .translationX(slideOut)
                .setDuration(220)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction {
                    panel.setLayerType(View.LAYER_TYPE_NONE, null)
                    panel.post {
                        safeRemoveView(panel)
                        panelView = null
                        contentContainer = null
                        edgeView.visibility = View.VISIBLE
                    }
                }
                .start()
        }
    }

    // =========================================================================
    // Slide-in Animation
    // =========================================================================

    private fun animatePanelIn(panel: View, panelWidth: Int) {
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        panel.animate()
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction { panel.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
    }

    // =========================================================================
    // Clipboard Write
    // =========================================================================

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        ClipboardAccessibilityService.instance?.isInternalCopy = true
        cm.setPrimaryClip(android.content.ClipData.newPlainText("clip", text))
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun safeRemoveView(view: View) {
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }

    private fun pill(color: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = 999f }

    companion object {
        private const val TAG = "EdgePanelService"
    }
}