package dev.dineshkumar.aescamera.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Wraps a page's zoomable content inside a ViewPager2. While the image is zoomed in, or
 * while two fingers are down (a pinch), the pager is asked not to intercept, so panning
 * and pinching stay on the image; a fit-to-screen image swipes to the next page.
 */
class ZoomPagerHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Supplied by the page: true when the content is zoomed past its fitted size. */
    var isZoomed: () -> Boolean = { false }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(isZoomed())

            MotionEvent.ACTION_POINTER_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }
}
