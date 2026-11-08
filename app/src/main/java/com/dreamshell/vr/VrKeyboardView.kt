package com.dreamshell.vr

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Material Design 3 floating mini QWERTY keyboard for DreamShell VR.
 * Allows typing into web search bars and text fields using the Daydream motion controller pointer.
 */
class VrKeyboardView(
    context: Context,
    private val onKeyInput: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private var isShifted = false
    private val letterKeyViews = mutableListOf<Pair<TextView, Pair<String, String>>>()

    init {
        isClickable = true
        isFocusable = true

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainer)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), MaterialTheme.OutlineVariant)
            }
            isClickable = true
            layoutParams = LayoutParams(dp(680), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            }
        }

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val title = TextView(context).apply {
            text = "⌨ Mini QWERTY Keyboard  ·  Point & click keys to type"
            textSize = 12f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainerHigh)
                cornerRadius = dp(100).toFloat()
            }
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)
        card.addView(header)

        // Keyboard Rows
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row2 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row3 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val row4 = listOf("z", "x", "c", "v", "b", "n", "m")

        // Row 1: Numbers + Backspace
        val r1Layout = createRowLayout()
        for (k in row1) {
            r1Layout.addView(createKey(k, k, weight = 1f) { onKeyInput(k) }.second)
        }
        r1Layout.addView(createSpecialKey("⌫", weight = 1.5f) { onBackspace() })
        card.addView(r1Layout)

        // Row 2: QWERTY
        val r2Layout = createRowLayout()
        for (k in row2) {
            val keyView = createKey(k, k.uppercase(), weight = 1f) {
                val textToType = if (isShifted) k.uppercase() else k
                onKeyInput(textToType)
                if (isShifted) toggleShift(false)
            }
            letterKeyViews.add(keyView.first to (k to k.uppercase()))
            r2Layout.addView(keyView.second)
        }
        card.addView(r2Layout)

        // Row 3: ASDFGHJKL + Enter
        val r3Layout = createRowLayout()
        for (k in row3) {
            val keyView = createKey(k, k.uppercase(), weight = 1f) {
                val textToType = if (isShifted) k.uppercase() else k
                onKeyInput(textToType)
                if (isShifted) toggleShift(false)
            }
            letterKeyViews.add(keyView.first to (k to k.uppercase()))
            r3Layout.addView(keyView.second)
        }
        r3Layout.addView(createSpecialKey("↵ Search", weight = 1.8f) { onEnter() })
        card.addView(r3Layout)

        // Row 4: Shift + ZXCVBNM + .com + Space
        val r4Layout = createRowLayout()
        val shiftBtnView = createSpecialKey("⇧ Shift", weight = 1.4f) { toggleShift(!isShifted) }
        r4Layout.addView(shiftBtnView)

        for (k in row4) {
            val keyView = createKey(k, k.uppercase(), weight = 1f) {
                val textToType = if (isShifted) k.uppercase() else k
                onKeyInput(textToType)
                if (isShifted) toggleShift(false)
            }
            letterKeyViews.add(keyView.first to (k to k.uppercase()))
            r4Layout.addView(keyView.second)
        }

        r4Layout.addView(createKey(".com", ".com", weight = 1.3f) { onKeyInput(".com") }.second)
        r4Layout.addView(createSpecialKey("␣ Space", weight = 2.2f) { onKeyInput(" ") })

        card.addView(r4Layout)

        addView(card)
    }

    private fun toggleShift(active: Boolean) {
        isShifted = active
        for ((tv, pair) in letterKeyViews) {
            tv.text = if (isShifted) pair.second else pair.first
        }
    }

    private fun createRowLayout(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
    }

    private fun createKey(lower: String, upper: String, weight: Float, onClick: () -> Unit): Pair<TextView, View> {
        val tv = TextView(context).apply {
            text = lower
            textSize = 14f
            setTextColor(MaterialTheme.OnSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainerHigh)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), MaterialTheme.OutlineVariant)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), weight).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            }
            setOnClickListener { onClick() }
        }
        return tv to tv
    }

    private fun createSpecialKey(label: String, weight: Float, onClick: () -> Unit): View {
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(MaterialTheme.OnSecondaryContainer)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SecondaryContainer)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), MaterialTheme.OutlineVariant)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), weight).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
