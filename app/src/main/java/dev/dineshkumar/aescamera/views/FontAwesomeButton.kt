package dev.dineshkumar.aescamera.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.enums.FontAwesomeStyle
import com.google.android.material.button.MaterialButton

class FontAwesomeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private var currentStyle: FontAwesomeStyle = FontAwesomeStyle.SOLID
    private var iconCode: String? = null

    /** The themed (enabled) fill color, so [resetFillColor] can return to it. */
    @ColorInt
    private var defaultFillColor: Int = Color.TRANSPARENT

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.FontAwesomeAttrs,
            0, 0
        ).apply {
            try {
                val styleString = getString(R.styleable.FontAwesomeAttrs_faStyle) ?: "solid"
                setFontAwesomeStyle(styleString)

                iconCode = getString(R.styleable.FontAwesomeAttrs_faIcon)
                iconCode?.let { setIcon(it) }
            } finally {
                recycle()
            }
        }
        keepColorsWhenDisabled()
    }

    /**
     * Material buttons turn nearly transparent when disabled, which over a camera preview
     * leaves a bare glyph with no button behind it. Keep the enabled fill and glyph colors
     * and only fade them, so a disabled control still reads as the same button.
     */
    private fun keepColorsWhenDisabled() {
        val enabled = intArrayOf(android.R.attr.state_enabled)
        defaultFillColor = backgroundTintList?.let { it.getColorForState(enabled, it.defaultColor) }
            ?: Color.TRANSPARENT
        setFillColor(defaultFillColor)
        setTextColor(fadedWhenDisabled(textColors.getColorForState(enabled, currentTextColor), 0.5f))
    }

    /** Sets the button's fill, keeping the faded look when disabled. */
    fun setFillColor(@ColorInt color: Int) {
        backgroundTintList = fadedWhenDisabled(color, 0.4f)
    }

    /** Back to the themed fill color. */
    fun resetFillColor() {
        setFillColor(defaultFillColor)
    }

    private fun fadedWhenDisabled(@ColorInt color: Int, alphaFraction: Float): ColorStateList {
        val faded = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * alphaFraction).toInt())
        return ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(faded, color)
        )
    }

    fun setFontAwesomeStyle(style: String) {
        currentStyle = when (style.lowercase()) {
            "solid" -> FontAwesomeStyle.SOLID
            "regular" -> FontAwesomeStyle.REGULAR
            "brands" -> FontAwesomeStyle.BRANDS
            else -> FontAwesomeStyle.SOLID
        }
        updateTypeface()
    }

    private fun updateTypeface() {
        val fontResId = when (currentStyle) {
            FontAwesomeStyle.SOLID -> R.font.fa_6_solid_900
            FontAwesomeStyle.REGULAR -> R.font.fa_6_free_regular_400
            FontAwesomeStyle.BRANDS -> R.font.fa_6_brands_regular_400
        }
        val typeface = ResourcesCompat.getFont(context, fontResId)
        setTypeface(typeface, Typeface.NORMAL)
    }

    fun setIcon(iconCode: String) {
        this.iconCode = iconCode
        val cleanCode = iconCode.replace("0x", "").replace("&#x", "")
        text = String(Character.toChars(cleanCode.toInt(16)))
    }
}