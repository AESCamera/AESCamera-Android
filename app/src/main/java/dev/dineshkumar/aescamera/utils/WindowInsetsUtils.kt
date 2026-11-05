package dev.dineshkumar.aescamera.utils

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
 * [extraTop]/[extraBottom] are added on the respective edges. On a screen with an
 * AppCompat action bar the content view is laid out behind the bar, but the insets
 * AppCompat dispatches to it already include the bar's height in the top inset, so no
 * extra top padding is needed to clear it.
 *
 * With [topAsMargin], the top inset becomes a top margin instead of padding. Use it for
 * a scrolling view with `clipToPadding=false` under an action bar: rows may then scroll
 * behind the navigation bar, but they are clipped at the bar instead of showing through
 * the transparent status bar above it.
 */
fun View.applySystemBarInsetsAsPadding(
    left: Boolean = true,
    top: Boolean = true,
    right: Boolean = true,
    bottom: Boolean = true,
    extraTop: Int = 0,
    extraBottom: Int = 0,
    topAsMargin: Boolean = false,
) {
    val initial = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    val initialTopMargin = (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val topInset = (if (top) bars.top else 0) + extraTop
        if (topAsMargin) {
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (params.topMargin != initialTopMargin + topInset) {
                    params.topMargin = initialTopMargin + topInset
                    view.layoutParams = params
                }
            }
        }
        view.setPadding(
            initial.left + if (left) bars.left else 0,
            initial.top + if (topAsMargin) 0 else topInset,
            initial.right + if (right) bars.right else 0,
            initial.bottom + (if (bottom) bars.bottom else 0) + extraBottom,
        )
        insets
    }
    requestApplyInsetsWhenAttached()
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

/**
 * For screens with a black background (camera, media viewer): light status and
 * navigation bar icons, whatever the theme asks for (the light theme asks for dark ones).
 */
fun Activity.useLightSystemBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}
