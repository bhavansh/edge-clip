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
import android.widget.*
import androidx.core.content.ContextCompat
import dev.bmg.edgeclip.R
import dev.bmg.edgeclip.data.ClipEntity
import dev.bmg.edgeclip.data.ClipRepository
import dev.bmg.edgeclip.data.ClipType
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

    fun refreshPanel(container: LinearLayout, clips: List<ClipEntity>) {
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
                        text = clip.text
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

        // Copy Button
        val copyBtn = TextView(context).apply {
            text = "Copy"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (clip.type == ClipType.TEXT) onCopyText(clip.text!!) else onCopyImage(clip)
                
                text = "Copied ✓"
                setTextColor(ContextCompat.getColor(context, R.color.pill_text_success))
                postDelayed({
                    text = "Copy"
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }, 1500)
            }
        }

        // Vertical divider between Copy and Delete
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(1), dpToPx(20))
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        }

        // Delete Button
        val deleteBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.pill_text_danger))
            gravity = Gravity.CENTER
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

    private fun pill(color: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = 999f }
}
