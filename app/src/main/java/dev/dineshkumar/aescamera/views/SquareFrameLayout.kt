package dev.dineshkumar.aescamera.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout as tall as it is wide — the gallery tile. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val square = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        super.onMeasure(square, square)
    }
}
