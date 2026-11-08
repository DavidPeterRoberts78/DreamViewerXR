package com.dreamshell.vr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Material Design 3 (M3) Dark Theme tokens and component builders for DreamShell.
 */
object MaterialTheme {
    // M3 Dark Palette Color Tokens
    val Primary = Color.parseColor("#A8C7FA")
    val OnPrimary = Color.parseColor("#003062")
    val PrimaryContainer = Color.parseColor("#004789")
    val OnPrimaryContainer = Color.parseColor("#D6E3FF")

    val Secondary = Color.parseColor("#C2C6DC")
    val OnSecondary = Color.parseColor("#2C3042")
    val SecondaryContainer = Color.parseColor("#3F4759")
    val OnSecondaryContainer = Color.parseColor("#DBE2F9")

    val Surface = Color.parseColor("#111318")
    val SurfaceContainerLow = Color.parseColor("#191C20")
    val SurfaceContainer = Color.parseColor("#1D2024")
    val SurfaceContainerHigh = Color.parseColor("#282A2F")
    val SurfaceContainerHighest = Color.parseColor("#33353A")

    val OnSurface = Color.parseColor("#E2E2E9")
    val OnSurfaceVariant = Color.parseColor("#C4C6D0")
    val Outline = Color.parseColor("#8C9099")
    val OutlineVariant = Color.parseColor("#44474E")

    val ErrorContainer = Color.parseColor("#93000A")
    val OnErrorContainer = Color.parseColor("#FFDAD6")
    val WarningContainer = Color.parseColor("#3B2D00")
    val OnWarningContainer = Color.parseColor("#FFDC7D")
    val SuccessContainer = Color.parseColor("#0F381E")
    val OnSuccessContainer = Color.parseColor("#8CF0B2")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    /** M3 Filled Button with pill shape (100dp) */
    fun filledButton(ctx: Context, textLabel: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = textLabel
        isAllCaps = false
        textSize = 14f
        setTextColor(OnPrimary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Primary)
            cornerRadius = dp(ctx, 100).toFloat()
        }
        setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
        setOnClickListener { onClick() }
    }

    /** M3 Tonal Button with pill shape */
    fun tonalButton(ctx: Context, textLabel: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = textLabel
        isAllCaps = false
        textSize = 14f
        setTextColor(OnSecondaryContainer)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(SecondaryContainer)
            cornerRadius = dp(ctx, 100).toFloat()
        }
        setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
        setOnClickListener { onClick() }
    }

    /** M3 Outlined Button with pill shape */
    fun outlinedButton(ctx: Context, textLabel: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = textLabel
        isAllCaps = false
        textSize = 14f
        setTextColor(Primary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 1), Outline)
            cornerRadius = dp(ctx, 100).toFloat()
        }
        setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
        setOnClickListener { onClick() }
    }

    /** M3 Card container with 24dp rounded corners */
    fun card(ctx: Context, elevated: Boolean = false, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18))
        background = GradientDrawable().apply {
            setColor(if (elevated) SurfaceContainerHigh else SurfaceContainer)
            cornerRadius = dp(ctx, 24).toFloat()
            setStroke(dp(ctx, 1), OutlineVariant)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 12) }
        build()
    }

    /** M3 Filter / Assist Chip */
    fun chip(ctx: Context, textLabel: String, selected: Boolean = false, onClick: () -> Unit): View = TextView(ctx).apply {
        text = textLabel
        textSize = 13f
        setTextColor(if (selected) OnPrimaryContainer else OnSurfaceVariant)
        typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
        background = GradientDrawable().apply {
            setColor(if (selected) PrimaryContainer else SurfaceContainerHigh)
            setStroke(dp(ctx, 1), if (selected) Primary else OutlineVariant)
            cornerRadius = dp(ctx, 12).toFloat()
        }
        setOnClickListener { onClick() }
    }

    /** M3 Badge indicator */
    fun badge(ctx: Context, textLabel: String, containerColor: Int, textColor: Int): View = TextView(ctx).apply {
        text = textLabel
        textSize = 11f
        setTextColor(textColor)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        background = GradientDrawable().apply {
            setColor(containerColor)
            cornerRadius = dp(ctx, 8).toFloat()
        }
    }
}
