package dev.bmg.edgepanel.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.bmg.edgepanel.clipboard.ClipboardAccessibilityService

class FocusWindowManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
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
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    fun createFocusWindow() {
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
        val view = View(context)
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
        handler.postDelayed(pollRunnable, 1500)
    }

    fun stopPeriodicClipboardPoll() {
        clipboardPollHandler?.removeCallbacksAndMessages(null)
        clipboardPollHandler = null
    }

    fun triggerFocusRead() {
        doFocusRead()
    }

    private fun doFocusRead() {
        val fw = focusWindow ?: return
        val params = focusWindowParams ?: return

        params.flags = readFlags
        try {
            windowManager.updateViewLayout(fw, params)
        } catch (e: Exception) {
            return
        }

        ClipboardAccessibilityService.instance?.readClipboardNow()

        Handler(Looper.getMainLooper()).postDelayed({
            params.flags = idleFlags
            try { windowManager.updateViewLayout(fw, params) } catch (_: Exception) {}
        }, 60)
    }

    fun removeFocusWindow() {
        stopPeriodicClipboardPoll()
        focusWindow?.let { 
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        focusWindow = null
        focusWindowParams = null
    }
}
