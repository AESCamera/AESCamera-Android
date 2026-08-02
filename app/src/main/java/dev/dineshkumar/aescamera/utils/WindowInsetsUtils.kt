package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge is enforced from targetSdk 35+, so the system bars go transparent and
 * content draws underneath them. These helpers keep controls/content out of the status
 * and navigation bar areas by turning the system-bar insets into padding, added on top
 * of whatever padding the view already had.
 */

/**
 * Pads the view by the system-bar (status + navigation + display-cutout) insets.
 *
 * [extraTop]/[extraBottom] are added on the respective edges. An opaque AppCompat action
 * bar sits below the status bar but the content view is laid out *behind* it, so a screen
 * with an action bar passes `extraTop = actionBarSize` to clear it.
 */
fun View.applySystemBarInsetsAsPadding(
    left: Boolean = true,
    top: Boolean = true,
    right: Boolean = true,
    bottom: Boolean = true,
    extraTop: Int = 0,
    extraBottom: Int = 0,
) {
    val initial = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            initial.left + if (left) bars.left else 0,
            initial.top + (if (top) bars.top else 0) + extraTop,
            initial.right + if (right) bars.right else 0,
            initial.bottom + (if (bottom) bars.bottom else 0) + extraBottom,
        )
        insets
    }
    requestApplyInsetsWhenAttached()
}

/** The themed action-bar height in pixels (0 if the theme has no action bar). */
fun Context.actionBarSize(): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
        TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    } else {
        0
    }
}

/** Requests an insets pass now if attached, otherwise once the view attaches. */
fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
}
