
// service/EdgePanelService.kt
package dev.bmg.edgepanel.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.bmg.edgepanel.clipboard.ClipboardAccessibilityService
import dev.bmg.edgepanel.data.ClipEntity
import dev.bmg.edgepanel.data.ClipRepository
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

    private lateinit var uiManager: PanelUIManager
    private lateinit var focusManager: FocusWindowManager

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        if (!checkPermissionsOrStop()) return
        
        ServiceState.setRunning(true)
        startForegroundNotification()
        
        repository = ClipRepository.getInstance(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        focusManager = FocusWindowManager(this, windowManager)
        uiManager = PanelUIManager(
            context = this,
            repository = repository,
            scope = scope,
            onCopyText = { text -> copyToClipboard(text) },
            onCopyImage = { clip -> copyImageToClipboard(clip) },
            onDelete = { clip -> scope.launch(Dispatchers.IO) { repository.delete(clip) } },
            onClearAll = { scope.launch(Dispatchers.IO) { repository.clearAll() } }
        )

        createEdgeHandle()
        focusManager.createFocusWindow()
        Log.d(TAG, "EdgePanelService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        checkPermissionsOrStop()
        return START_STICKY
    }

    private fun checkPermissionsOrStop(): Boolean {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission missing! Stopping service.")
            ServiceState.setRunning(false)
            stopSelf()
            return false
        }
        if (!isAccessibilityServiceEnabled()) {
            Log.e(TAG, "Accessibility permission missing! Stopping service.")
            ServiceState.setRunning(false)
            stopSelf()
            return false
        }
        return true
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = android.content.ComponentName(
            this,
            ClipboardAccessibilityService::class.java
        ).flattenToString()

        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    private fun startForegroundNotification() {
        val channelId = "edge_panel_service"
        val channelName = "Edge Panel Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the edge panel active"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Edge Panel is active")
            .setContentText("Swipe the edge handle to see clipboard history")
            .setSmallIcon(dev.bmg.edgepanel.R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        ServiceState.setRunning(false)
        scope.cancel()
        focusManager.removeFocusWindow()
        panelView?.let { safeRemoveView(it) }
        if (::edgeView.isInitialized) safeRemoveView(edgeView)
        super.onDestroy()
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

    private fun togglePanel() {
        if (panelView == null) openPanel() else closePanel()
    }

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
            root.addView(uiManager.buildClearButton())
        }

        panelView = panel
        windowManager.addView(panel, params)
        animatePanelIn(panel, panelWidth)
        edgeView.visibility = View.GONE
        
        focusManager.triggerFocusRead()
        observeClips()
    }

    private fun observeClips() {
        flowJob?.cancel()
        flowJob = scope.launch {
            repository.clips.collectLatest { clips ->
                contentContainer?.let { uiManager.refreshPanel(it, clips) }
            }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun buildPanelRoot(panelWidth: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dpToPx(20), 0, dpToPx(12))
        background = resources.getDrawable(dev.bmg.edgepanel.R.drawable.panel_bg, theme)
        translationX = panelWidth.toFloat()
        elevation = dpToPx(12).toFloat()
    }

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

    private fun animatePanelIn(panel: View, panelWidth: Int) {
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        panel.animate()
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .withEndAction { panel.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        ClipboardAccessibilityService.instance?.isInternalCopy = true
        cm.setPrimaryClip(android.content.ClipData.newPlainText("clip", text))
    }

    private fun copyImageToClipboard(clip: ClipEntity) {
        val path = clip.imagePath ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(path)
                val uri = FileProvider.getUriForFile(this@EdgePanelService, "${packageName}.fileprovider", file)
                val clipData = android.content.ClipData.newUri(contentResolver, "image", uri)
                withContext(Dispatchers.Main) {
                    ClipboardAccessibilityService.instance?.isInternalCopy = true
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(clipData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image copy failed", e)
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun safeRemoveView(view: View) {
        try { windowManager.removeView(view) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "EdgePanelService"
    }
}
