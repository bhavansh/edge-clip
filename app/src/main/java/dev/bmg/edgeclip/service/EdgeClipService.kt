// service/EdgeClipService.kt
package dev.bmg.edgeclip.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.bmg.edgeclip.clipboard.ClipboardAccessibilityService
import dev.bmg.edgeclip.data.ClipEntity
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.data.SettingsManager
import dev.bmg.edgeclip.view.GestureScrollView
import dev.bmg.edgeclip.view.QuickballView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import kotlin.math.abs

class EdgeClipService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var edgeView: QuickballView
    private var panelView: View? = null

    private lateinit var repository: ClipRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var contentContainer: LinearLayout? = null
    private var flowJob: Job? = null

    private lateinit var uiManager: PanelUIManager
    private lateinit var focusManager: FocusWindowManager
    private lateinit var settingsManager: SettingsManager

    private var currentFocusedPackage: String? = null
    private var isBlacklistedAppFocused = false
    private var autoPaused = false
    private var isProgrammaticUpdate = false

    private val restingWidthDp = 26
    private val fullWidthDp = 40

    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsManager.KEY_BG_POLLING || key == SettingsManager.KEY_POLLING_FREQ) {
            if (::focusManager.isInitialized) {
                focusManager.restartPolling()
            }
        } else if (key == SettingsManager.KEY_EDGE_SIDE) {
            updateHandleSide()
        } else if (key == SettingsManager.KEY_IS_PAUSED || key == SettingsManager.KEY_BLACKLIST) {
            // If user manually toggled pause while in a blacklisted app, clear autoPaused
            if (key == SettingsManager.KEY_IS_PAUSED && isBlacklistedAppFocused && !isProgrammaticUpdate) {
                autoPaused = false
            }

            if (key == SettingsManager.KEY_BLACKLIST) {
                currentFocusedPackage?.let { recalculateBlacklist(it) }
            }
            updateVisibilityState()
        }
    }

    private fun updateVisibilityState() {
        val isPaused = settingsManager.isPaused
        // We no longer force hide the handle for blacklist, just honor the pause state
        setHandleForceHidden(false) 

        if (isPaused) {
            focusManager.stopPeriodicClipboardPoll()
        } else {
            focusManager.restartPolling()
        }
    }

    fun onPackageFocused(packageName: String) {
        val trimmedPkg = packageName.trim()
        if (trimmedPkg == currentFocusedPackage) return
        currentFocusedPackage = trimmedPkg
        
        // Ignore our own package so we don't accidentally resume monitoring
        // when the Edge Panel or Settings is opened over a blacklisted app.
        if (trimmedPkg == this.packageName) return
        
        recalculateBlacklist(trimmedPkg)
    }

    private fun recalculateBlacklist(packageName: String) {
        val isNowBlacklisted = settingsManager.blacklistedPackages.contains(packageName)
        val wasBlacklisted = isBlacklistedAppFocused
        isBlacklistedAppFocused = isNowBlacklisted

        if (isNowBlacklisted && !settingsManager.isPaused) {
            // Auto-pause when entering
            isProgrammaticUpdate = true
            settingsManager.isPaused = true
            isProgrammaticUpdate = false
            autoPaused = true
            Toast.makeText(this, "EdgeClip paused: Blacklisted app", Toast.LENGTH_SHORT).show()
        } else if (!isNowBlacklisted && wasBlacklisted && autoPaused && settingsManager.isPaused) {
            // Auto-resume when leaving if we were the ones who paused it
            isProgrammaticUpdate = true
            settingsManager.isPaused = false
            isProgrammaticUpdate = false
            autoPaused = false
            Toast.makeText(this, "EdgeClip resumed", Toast.LENGTH_SHORT).show()
        } else if (!isNowBlacklisted && autoPaused) {
            // Relinquish auto-pause control if user manually unpaused while inside
            autoPaused = false
        }
        
        updateVisibilityState()
    }

    private fun updateHandleSide() {
        if (::edgeView.isInitialized) {
            safeRemoveView(edgeView)
            createEdgeHandle()
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        repository = ClipRepository.getInstance(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager(this)
        settingsManager.registerListener(settingsListener)
        
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

        if (!checkPermissionsOrStop()) return
        
        ServiceState.setRunning(true)
        startForegroundNotification()
        
        createEdgeHandle()
        focusManager.createFocusWindow()
        Log.d(TAG, "EdgeClipService created")
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
        val channelId = "edge_clip_service"
        val channelName = "EdgeClip Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the edge clip active"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EdgeClip is active")
            .setContentText("Swipe the edge handle to see clipboard history")
            .setSmallIcon(dev.bmg.edgeclip.R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, dev.bmg.edgeclip.R.mipmap.ic_launcher))
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
        if (::settingsManager.isInitialized) {
            settingsManager.unregisterListener(settingsListener)
        }
        if (::focusManager.isInitialized) focusManager.removeFocusWindow()
        panelView?.let { safeRemoveView(it) }
        if (::edgeView.isInitialized) safeRemoveView(edgeView)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun triggerFocusRead() {
        if (::focusManager.isInitialized) {
            focusManager.triggerFocusRead()
        }
    }

    fun triggerRefresh() {
        observeClips()
    }

    fun isHandleVisible(): Boolean {
        return ::edgeView.isInitialized && edgeView.visibility == View.VISIBLE && edgeView.isAttachedToWindow
    }

    fun setHandleForceHidden(hidden: Boolean) {
        if (::edgeView.isInitialized) {
            edgeView.post {
                val newVisibility = if (hidden) View.GONE else View.VISIBLE
                if (edgeView.visibility != newVisibility) {
                    Log.d(TAG, "Visibility Change: $newVisibility")
                    edgeView.visibility = newVisibility
                }
            }
        } else {
            Log.d(TAG, "setHandleForceHidden: edgeView not initialized")
        }
    }

    // =========================================================================
    // Edge Handle
    // =========================================================================

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun createEdgeHandle() {
        val params = handleLayoutParams()
        val isLeft = settingsManager.edgeSide == "left"
        edgeView = QuickballView(this).apply {
            contentDescription = getString(dev.bmg.edgeclip.R.string.edge_clip_handler_desc)
            setSide(isLeft)
        }
        windowManager.addView(edgeView, params)
        attachHandleTouchListener(params)
    }

    private fun handleLayoutParams() = WindowManager.LayoutParams(
        dpToPx(restingWidthDp), dpToPx(fullWidthDp),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).also { 
        val isLeft = settingsManager.edgeSide == "left"
        it.gravity = (if (isLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        it.x = 0 // Flush with the screen edge
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachHandleTouchListener(params: WindowManager.LayoutParams) {
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true
        val isLeft = settingsManager.edgeSide == "left"

        edgeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX)
                    val dy = (event.rawY - initialTouchY)
                    
                    // Vertical drag for repositioning
                    if (abs(dy) > dpToPx(15)) {
                        isClick = false
                    }
                    params.y = initialY + dy.toInt()

                    // Horizontal drag for "pull out" effect
                    val pullDist = if (isLeft) dx else -dx
                    if (pullDist > dpToPx(10)) {
                        isClick = false
                    }
                    
                    val expansion = (pullDist / dpToPx(fullWidthDp)).coerceIn(0f, 1f)
                    edgeView.setExpansion(expansion)
                    
                    val currentWidth = restingWidthDp + (fullWidthDp - restingWidthDp) * expansion
                    params.width = dpToPx(currentWidth.toInt())
                    
                    windowManager.updateViewLayout(edgeView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        edgeView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        togglePanel()
                    } else {
                        // Snap back
                        edgeView.setExpansion(0f)
                        params.width = dpToPx(restingWidthDp)
                        windowManager.updateViewLayout(edgeView, params)
                    }
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
        val isLeft = settingsManager.edgeSide == "left"

        val params = WindowManager.LayoutParams(
            panelWidth, panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { 
            it.gravity = (if (isLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            it.x = dpToPx(5) // Displacement from edge
        }

        val panel = buildPanelRoot(panelWidth).also { root ->
            root.addView(buildScrollableContent())
            root.addView(uiManager.buildClearButton())
            
            root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (settingsManager.closeOnOutsideClick) {
                        closePanel()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }

        panelView = panel
        windowManager.addView(panel, params)
        animatePanelIn(panel)
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
        background = resources.getDrawable(dev.bmg.edgeclip.R.drawable.edge_clip_panel_bg, theme)
        
        val isLeft = settingsManager.edgeSide == "left"
        translationX = if (isLeft) -panelWidth.toFloat() else panelWidth.toFloat()
        elevation = dpToPx(12).toFloat()
    }

    private fun buildScrollableContent(): GestureScrollView {
        val scrollView = GestureScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            
            val isLeft = settingsManager.edgeSide == "left"
            if (isLeft) {
                onFlingLeft = { closePanel() }
            } else {
                onFlingRight = { closePanel() }
            }
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

            val isLeft = settingsManager.edgeSide == "left"
            val slideOut = (resources.displayMetrics.widthPixels * 0.40f).let { 
                if (isLeft) -it else it
            }
            
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

    private fun animatePanelIn(panel: View) {
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
                val uri = FileProvider.getUriForFile(this@EdgeClipService, "${packageName}.fileprovider", file)
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
        private const val TAG = "EdgeClipService"
        var instance: EdgeClipService? = null
    }
}
