package dev.bmg.edgeclip.service

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.util.Log
import android.widget.*
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.widget.HorizontalScrollView
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import dev.bmg.edgeclip.R
import dev.bmg.edgeclip.data.ClipEntity
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.data.ClipType
import dev.bmg.edgeclip.data.SettingsManager
import kotlinx.coroutines.*

class PanelUIManager(
    private val context: Context,
    private val repository: ClipRepository,
    private val scope: CoroutineScope,
    private val onCopyText: (String) -> Unit,
    private val onCopyImage: (ClipEntity) -> Unit,
    private val onDelete: (ClipEntity) -> Unit,
    private val onClearAll: () -> Unit
) {
    private val settings = SettingsManager(context)
    private var currentFilter = "ALL" // ALL, URL, PHONE, OTP, IMAGE

    fun refreshPanel(container: LinearLayout, clips: List<ClipEntity>) {
        container.removeAllViews()
        
        // 1. Add Filter Chips & Pause Button
        container.addView(buildHeaderRow())
        
        val filteredClips = when (currentFilter) {
            "ALL" -> clips
            "IMAGE" -> clips.filter { it.type == ClipType.IMAGE }
            else -> clips.filter { it.subtype == currentFilter }
        }

        if (filteredClips.isEmpty()) {
            container.addView(buildEmptyState())
            return
        }

        filteredClips.forEach { clip ->
            container.addView(buildClipBlock(clip))
            container.addView(buildBlockDivider())
        }
    }

    private fun buildHeaderRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dpToPx(6)
            }
        }
        
        val buttonsRow1 = listOf("ALL", "PAUSE", "URL")
        val buttonsRow2 = listOf("IMAGE", "OTP", "PHONE")
        
        buttonsRow1.forEach { type -> row1.addView(createGridButton(type)) }
        buttonsRow2.forEach { type -> row2.addView(createGridButton(type)) }
        
        addView(row1)
        addView(row2)
    }

    private fun createGridButton(type: String): View = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f).also {
            it.rightMargin = dpToPx(4)
        }
        
        val isPausedType = type == "PAUSE"
        val isSelected = currentFilter == type || (isPausedType && settings.isPaused)
        
        background = pill(when {
            isPausedType && settings.isPaused -> ContextCompat.getColor(context, R.color.pill_text_danger)
            isSelected -> ContextCompat.getColor(context, R.color.purple_500)
            else -> ContextCompat.getColor(context, R.color.button_clear_bg)
        }, cornerRadius = 12f)
        
        if (isPausedType) {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_pause)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(if (isSelected) Color.WHITE else ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = FrameLayout.LayoutParams(dpToPx(20), dpToPx(20), Gravity.CENTER)
            })
        } else {
            addView(TextView(context).apply {
                text = type
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) Color.WHITE else ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        }
        
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isPausedType) {
                settings.isPaused = !settings.isPaused
                val msg = if (settings.isPaused) "Monitoring Paused" else "Monitoring Resumed"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } else {
                currentFilter = type
            }
            EdgeClipService.instance?.triggerRefresh()
        }
    }

    private fun buildEmptyState(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        addView(TextView(context).apply {
            text = "No items"
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Copied text will appear here"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(6), 0, 0)
        })
    }

    private fun buildClipBlock(clip: ClipEntity): FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            
            // Re-adding the actual content rendering
            when (clip.type) {
                ClipType.TEXT -> {
                    addView(TextView(context).apply {
                        val originalText = clip.text ?: ""
                        val spannable = SpannableString(originalText)
                        
                        when (clip.subtype) {
                            "OTP" -> {
                                val regex = Regex("(?<![\\d.])\\d{4,8}(?![\\d.])")
                                regex.find(originalText)?.let { match ->
                                    val start = match.range.first
                                    val end = match.range.last + 1
                                    spannable.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.color_otp_highlight)), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                            "PHONE" -> {
                                val regex = Regex("(?:\\+?\\d{1,3}[- ]?)?\\d{3,5}[- ]?\\d{3,5}(?:[- ]?\\d{1,5})?")
                                regex.find(originalText)?.let { match ->
                                    val start = match.range.first
                                    val end = match.range.last + 1
                                    spannable.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.color_phone_highlight)), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                        }
                        
                        text = spannable
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        setLineSpacing(0f, 1.2f)
                        setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
                    })
                }
                ClipType.IMAGE -> {
                    val path = clip.imagePath
                    if (path != null) {
                        addView(buildImageView(path))
                    }
                }
            }

            // Full-width action bar at the bottom
            addView(buildActionBar(clip))
        }
        
        addView(contentLayout)
    }

    private fun buildActionBar(clip: ClipEntity): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(38)
        )
        setBackgroundColor(ContextCompat.getColor(context, R.color.action_bar_bg))
        gravity = Gravity.CENTER_VERTICAL

        // 1. Contextual Action Icon
        if (clip.subtype != "NONE") {
            val actionBtn = ImageView(context).apply {
                val iconRes = when (clip.subtype) {
                    "URL" -> R.drawable.ic_link
                    "PHONE" -> R.drawable.ic_call
                    "OTP" -> R.drawable.ic_key
                    else -> 0
                }
                if (iconRes != 0) {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val tint = when (clip.subtype) {
                        "OTP" -> ContextCompat.getColor(context, R.color.color_otp_highlight)
                        "PHONE" -> ContextCompat.getColor(context, R.color.color_phone_highlight)
                        else -> ContextCompat.getColor(context, R.color.purple_500)
                    }
                    setColorFilter(tint)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener { performAction(clip) }
                }
            }
            if (actionBtn.drawable != null) {
                addView(actionBtn)
                addView(View(context).apply { 
                    layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                })
            }
        }

        // 2. Copy Button (Icon)
        val copyBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_copy)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val tint = ContextCompat.getColor(context, R.color.text_primary)
            setColorFilter(tint)
            
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (clip.type == ClipType.TEXT) onCopyText(clip.text!!) else onCopyImage(clip)
                
                setImageResource(R.drawable.ic_check)
                setColorFilter(ContextCompat.getColor(context, R.color.pill_text_success))
                
                postDelayed({
                    setImageResource(R.drawable.ic_copy)
                    setColorFilter(tint)
                }, 1500)
            }
        }

        // Vertical divider between Copy and Delete
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20))
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        }

        // Delete Button (Icon)
        val deleteBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(ContextCompat.getColor(context, R.color.pill_text_danger))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDelete(clip)
            }
        }

        addView(copyBtn)
        addView(divider)
        addView(deleteBtn)
    }

    private fun performAction(clip: ClipEntity) {
        val text = clip.text ?: return
        try {
            val intent = when (clip.subtype) {
                "URL" -> Intent(Intent.ACTION_VIEW, Uri.parse(text))
                "PHONE" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$text"))
                "OTP" -> {
                    // Extract digits for OTP specifically
                    val digits = Regex("\\d{4,8}").find(text)?.value ?: text
                    onCopyText(digits)
                    Toast.makeText(context, "OTP Copied", Toast.LENGTH_SHORT).show()
                    null
                }
                else -> null
            }
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (e: Exception) {
            Log.e("PanelUI", "Action failed", e)
        }
    }

    private fun buildImageView(path: String): ImageView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(140)
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
        
        scope.launch(Dispatchers.IO) {
            val bmp = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            if (bmp != null) {
                withContext(Dispatchers.Main) { setImageBitmap(bmp) }
            }
        }
    }

    private fun buildBlockDivider(): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    fun buildClearButton(): TextView = TextView(context).apply {
        text = "Clear All"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.button_clear_text))
        background = pill(ContextCompat.getColor(context, R.color.button_clear_bg))
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
            onClearAll()
            text = "Cleared"
            postDelayed({ text = "Clear All" }, 1500)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(color: Int, cornerRadius: Float = 999f) =
        GradientDrawable().apply { setColor(color); this.cornerRadius = dpToPx(cornerRadius.toInt()).toFloat() }
}
