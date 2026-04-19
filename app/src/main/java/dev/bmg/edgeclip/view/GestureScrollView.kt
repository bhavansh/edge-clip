package dev.bmg.edgeclip.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.abs

/**
 * ScrollView that passively observes touches via GestureDetector.
 *
 * Key design decision: we NEVER return true from our own logic in
 * onTouchEvent or onInterceptTouchEvent. This guarantees:
 *   - System back gesture, home gesture, notification shade swipe-down
 *     are NEVER intercepted or blocked by this view.
 *   - The ScrollView scrolls vertically as normal.
 *   - We only *observe* the gesture stream and call [onFlingRight]
 *     as a pure side-effect when a fast rightward fling is detected.
 */
class GestureScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** Called when the user flings rightward fast enough to dismiss the panel. */
    var onFlingRight: (() -> Unit)? = null
    
    /** Called when the user flings leftward fast enough to dismiss the panel. */
    var onFlingLeft: (() -> Unit)? = null

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Rightward fling with horizontal velocity dominating
                if (velocityX > MIN_VELOCITY && velocityX > abs(velocityY) * 1.2f) {
                    onFlingRight?.invoke()
                }
                // Leftward fling with horizontal velocity dominating
                if (velocityX < -MIN_VELOCITY && abs(velocityX) > abs(velocityY) * 1.2f) {
                    onFlingLeft?.invoke()
                }
                return false  // never consume
            }
        }
    )

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)          // observe only
        return super.onTouchEvent(ev)      // normal scroll behaviour unchanged
    }

    // onInterceptTouchEvent intentionally NOT overridden —
    // system gesture recognizer gets full priority.

    companion object {
        private const val MIN_VELOCITY = 600f  // px/s
    }
}