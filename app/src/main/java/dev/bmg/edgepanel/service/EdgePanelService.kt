package dev.bmg.edgepanel.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import dev.bmg.edgepanel.view.GestureScrollView
import kotlin.math.abs

class EdgePanelService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var edgeView: View
    private var panelView: View? = null

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createEdgeHandle()
    }

    override fun onDestroy() {
        super.onDestroy()
        panelView?.let { safeRemoveView(it) }
        if (::edgeView.isInitialized) safeRemoveView(edgeView)
        Log.d(TAG, "Service Destroyed")
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
                    initialY = params.y; initialTouchY = event.rawY; isClick = true; true
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
                MotionEvent.ACTION_UP -> { if (isClick) togglePanel(); true }
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
        val panelWidth  = (screenWidth * 0.40).toInt()
        val panelHeight = (getScreenHeight() * 0.82).toInt()

        // Drop FLAG_NOT_FOCUSABLE so clipboard writes work on Android 10+.
        // FLAG_WATCH_OUTSIDE_TOUCH lets touches outside still reach apps below.
        val params = WindowManager.LayoutParams(
            panelWidth, panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.END or Gravity.CENTER_VERTICAL }

        val panel = buildPanelRoot(panelWidth).also { root ->
//            root.addView(buildHeader())
//            root.addView(buildHeaderDivider())
            root.addView(buildScrollableContent())   // weight=1, fills middle
            root.addView(buildClearButton())          // always visible at bottom
        }

        panelView = panel
        windowManager.addView(panel, params)
        animatePanelIn(panel, panelWidth)
        edgeView.visibility = View.GONE

        setupClipboardListener()
        seedInitialClipboard()
    }

    // =========================================================================
    // Panel root
    // =========================================================================

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun buildPanelRoot(panelWidth: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // No horizontal padding here — clip blocks span full width
        setPadding(0, dpToPx(20), 0, dpToPx(12))
        background = resources.getDrawable(dev.bmg.edgepanel.R.drawable.panel_bg, theme)
        translationX = panelWidth.toFloat()
        elevation = dpToPx(12).toFloat()
    }

    // =========================================================================
    // Header  (sticky, not inside ScrollView)
    // =========================================================================

//    private fun buildHeader(): TextView = TextView(this).apply {
//        text = "Edge Panel"
//        textSize = 17f
//        setTextColor(Color.parseColor("#1C1C1E"))
//        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
//        setPadding(dpToPx(16), 0, dpToPx(16), 0)
//        layoutParams = LinearLayout.LayoutParams(
//            LinearLayout.LayoutParams.MATCH_PARENT,
//            LinearLayout.LayoutParams.WRAP_CONTENT
//        )
//    }
//
//    private fun buildHeaderDivider(): View = View(this).apply {
//        setBackgroundColor(Color.parseColor("#E5E5EA"))
//        layoutParams = LinearLayout.LayoutParams(
//            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
//        ).also { it.setMargins(0, dpToPx(12), 0, 0) }
//    }

    // =========================================================================
    // Scrollable content
    // GestureScrollView observes flings without ever blocking system gestures.
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

        if (clipboardHistory.isEmpty()) {
            contentContainer = container
            refreshPanel()
        } else {
            refreshPanel()
        }
        scrollView.addView(container)
        return scrollView
    }

    private fun buildEmptyState(): LinearLayout {
        return LinearLayout(this).apply {
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
            })

            addView(TextView(context).apply {
                text = "Copied text will appear here"
                textSize = 13f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(0, dpToPx(6), 0, 0)
            })
        }
    }

    // =========================================================================
    // Clip block
    //
    //  FrameLayout  (full panel width, internal h-padding)
    //    TextView   text — full content, bottom-padded to leave room for pill
    //    TextView   "Copy" pill — Gravity.BOTTOM | Gravity.END overlay
    // =========================================================================

    private fun buildClipBlock(text: String): FrameLayout = FrameLayout(this).apply {
        setPadding(dpToPx(14), dpToPx(12), dpToPx(10), dpToPx(10))

        // Full text, no line limit
        addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#1C1C1E"))
            setLineSpacing(0f, 1.35f)
            // Bottom padding so last line of text sits above the copy pill
            setPadding(0, 0, 0, dpToPx(26))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // Copy pill anchored bottom-right over the text
        addView(buildCopyPill(text))
    }

    private fun buildCopyPill(clipText: String): TextView = TextView(this).apply {
        text = "Copy"
        textSize = 11.5f
        setTextColor(Color.parseColor("#48484A"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#E8E8ED"))
        setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))

        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        )

        setOnClickListener {
            copyToClipboard(clipText)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            text = "Copied"
            setTextColor(Color.parseColor("#34C759"))
            background = pill(Color.parseColor("#E3F9E8"))
            postDelayed({
                text = "Copy"
                setTextColor(Color.parseColor("#48484A"))
                background = pill(Color.parseColor("#E8E8ED"))
            }, 1800)
        }
    }

    private fun buildBlockDivider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#F2F2F7"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    // =========================================================================
    // Clear button — small centered pill, neutral gray, no divider above it
    // =========================================================================

    private fun buildClearButton(): TextView = TextView(this).apply {
        text = "Clear"
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
            // TODO: clear Room DB when implemented
            text = "Cleared"
            postDelayed({ text = "Clear" }, 1500)
        }
    }

    // =========================================================================
    // Close panel
    // =========================================================================

    private fun closePanel() {
        clipListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
        }
        clipListener = null

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
                        edgeView.visibility = View.VISIBLE
                    }
                }
                .start()
        }
    }

    // =========================================================================
    // Slide-in animation
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
    // Clipboard write
    // Works because panel is NOT FLAG_NOT_FOCUSABLE (Android 10+ requirement)
    // =========================================================================

    private fun copyToClipboard(text: String) {
        isInternalCopy = true

        val intent = Intent(this, dev.bmg.edgepanel.clipboard.ClipboardProxyActivity::class.java).apply {
            putExtra(dev.bmg.edgepanel.clipboard.ClipboardProxyActivity.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        startActivity(intent)
    }
    // =========================================================================
    // Utilities
    // =========================================================================

    private fun getScreenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun safeRemoveView(view: View) {
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }

    private fun pill(color: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = 999f }

    // =========================================================================
    // Dummy data — replace with Room + ClipboardManager listener
    // =========================================================================

    // Clipboard
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // History
    private val clipboardHistory = mutableListOf<String>()

    // Prevent duplicate self-trigger
    private var isInternalCopy = false

    // UI reference
    private var contentContainer: LinearLayout? = null

    private fun setupClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip ?: return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener

            if (isInternalCopy) {
                isInternalCopy = false
                return@OnPrimaryClipChangedListener
            }

            addToHistory(text)
        }

        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    private fun seedInitialClipboard() {
        val clip = clipboardManager?.primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return

        addToHistory(text)
    }

    private fun addToHistory(text: String) {
        if (text.isBlank()) return

        if (clipboardHistory.firstOrNull() == text) return

        clipboardHistory.remove(text)
        clipboardHistory.add(0, text)

        // limit size (optional)
        if (clipboardHistory.size > 50) {
            clipboardHistory.removeLast()
        }

        refreshPanel()
    }

    private fun refreshPanel() {
        val container = contentContainer ?: return

        container.removeAllViews()

        if (clipboardHistory.isEmpty()) {
            container.addView(buildEmptyState())
            return
        }

        clipboardHistory.forEach { text ->
            container.addView(buildClipBlock(text))
            container.addView(buildBlockDivider())
        }
    }

    private fun dummyClipItems(): List<String> = listOf(
        "Meeting at 3 PM — don't forget to bring the Q2 slides.",
        "https://developer.android.com/develop/ui/views/layout/recyclerview",
        "OTP: 847291 — valid for 10 minutes. Do not share.",
        "Bhavansh's Edge Panel — v1.0 alpha build.",
        "git commit -m \"feat: add clipboard monitoring via ClipboardManager\"",
        "Invoice #INV-2024-0091\nAmount: ₹14,500\nDue: 30 June 2025",
        "The quick brown fox jumps over the lazy dog — classic pangram.",
        "Contact: Rohan Mehta | +91 98765 43210 | rohan@example.com",
        "Design token: --color-surface: #F2F2F7",
        "TODO: Implement BootReceiver for autostart.",
    )

    companion object {
        private const val TAG = "EdgePanelService"
    }
}