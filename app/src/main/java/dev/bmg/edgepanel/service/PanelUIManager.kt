package dev.bmg.edgepanel.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.core.content.FileProvider
import dev.bmg.edgepanel.clipboard.ClipboardAccessibilityService
import dev.bmg.edgepanel.data.ClipEntity
import dev.bmg.edgepanel.data.ClipRepository
import dev.bmg.edgepanel.data.ClipType
import kotlinx.coroutines.*
import java.io.File

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

    private fun buildClipBlock(clip: ClipEntity): FrameLayout = FrameLayout(context).apply {
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

    private fun buildImageView(path: String): ImageView = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dpToPx(140)
        ).also { it.bottomMargin = dpToPx(30) }
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpToPx(8).toFloat())
            }
        }

        scope.launch(Dispatchers.IO) {
            val bmp = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            if (bmp != null) {
                withContext(Dispatchers.Main) { setImageBitmap(bmp) }
            }
        }
    }

    private fun buildImagePillRow(clip: ClipEntity): LinearLayout = LinearLayout(context).apply {
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

    private fun buildCopyImagePill(clip: ClipEntity): TextView = TextView(context).apply {
        text = "Copy"
        textSize = 11.5f
        setTextColor(Color.parseColor("#48484A"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#E8E8ED"))
        setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))

        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onCopyImage(clip)
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

    private fun buildPillRow(clip: ClipEntity): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        )

        addView(buildCopyPill(clip.text!!))
        addView(Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(6), 1)
        })
        addView(buildDeletePill(clip))
    }

    private fun buildCopyPill(clipText: String): TextView = TextView(context).apply {
        text = "Copy"
        textSize = 11.5f
        setTextColor(Color.parseColor("#48484A"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#E8E8ED"))
        setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5))

        setOnClickListener {
            onCopyText(clipText)
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

    private fun buildDeletePill(clip: ClipEntity): TextView = TextView(context).apply {
        text = "✕"
        textSize = 11.5f
        setTextColor(Color.parseColor("#FF3B30"))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        background = pill(Color.parseColor("#FFEEED"))
        setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))

        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDelete(clip)
        }
    }

    private fun buildBlockDivider(): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#F2F2F7"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
        )
    }

    fun buildClearButton(): TextView = TextView(context).apply {
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
