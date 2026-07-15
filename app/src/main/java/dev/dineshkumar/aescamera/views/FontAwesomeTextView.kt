package dev.dineshkumar.aescamera.views

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import dev.dineshkumar.aescamera.R
import dev.dineshkumar.aescamera.enums.FontAwesomeStyle

class FontAwesomeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var currentStyle: FontAwesomeStyle = FontAwesomeStyle.SOLID

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.FontAwesomeAttrs,
            0, 0
        ).apply {
            try {
                val styleString = getString(R.styleable.FontAwesomeAttrs_faStyle) ?: "solid"
                setFontAwesomeStyle(styleString)

                val iconCode = getString(R.styleable.FontAwesomeAttrs_faIcon)
                if (iconCode != null) {
                    setIcon(iconCode)
                }
            } finally {
                recycle()
            }
        }
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
        val cleanCode = iconCode.replace("0x", "").replace("&#x", "")
        text = String(Character.toChars(cleanCode.toInt(16)))
    }
}