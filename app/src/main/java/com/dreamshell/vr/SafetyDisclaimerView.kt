package com.dreamshell.vr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Material Design 3 compliant Health and Safety Disclaimer view displayed at launch in DreamShell VR.
 * Advises users regarding Epilepsy & Photosensitivity, VR Nausea & Motion Sickness,
 * Eye Strain, and Safe Environment precautions.
 */
class SafetyDisclaimerView(
    context: Context,
    private val onAccept: () -> Unit
) : FrameLayout(context) {

    private companion object {
        val OVERLAY_BG = Color.parseColor("#E60B0D12")
    }

    init {
        setBackgroundColor(OVERLAY_BG)
        isClickable = true
        isFocusable = true

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainer)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), MaterialTheme.OutlineVariant)
            }
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(dp(740), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = dp(20)
                bottomMargin = dp(20)
            }
        }

        // Title Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val iconText = TextView(context).apply {
            text = "⚠️"
            textSize = 24f
            setPadding(0, 0, dp(12), 0)
        }
        header.addView(iconText)

        val headerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(context).apply {
            text = "Health & Safety Disclaimer"
            textSize = 20f
            setTextColor(MaterialTheme.OnSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        headerCol.addView(TextView(context).apply {
            text = "Please read these important health & safety guidelines before using VR."
            textSize = 13f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(headerCol)
        card.addView(header)

        // Divider
        card.addView(View(context).apply {
            setBackgroundColor(MaterialTheme.OutlineVariant)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(16)
            }
        })

        // Scrollable content of warnings
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        contentLayout.addView(createWarningSection(
            icon = "⚡",
            title = "Photosensitivity & Epilepsy Warning",
            description = "VR experiences contain flashing lights and rapid visual movement. A small percentage of individuals may experience epileptic seizures or blackouts when exposed to light patterns. Discontinue use immediately and consult a physician if you experience dizziness, altered vision, eye or muscle twitching, or involuntary movements."
        ))

        contentLayout.addView(createWarningSection(
            icon = "🤢",
            title = "VR Nausea & Motion Sickness",
            description = "Virtual reality motion can induce motion sickness, disorientation, nausea, or loss of balance. Take frequent breaks. Stop using the headset immediately if you feel uncomfortable, lightheaded, or nauseous."
        ))

        contentLayout.addView(createWarningSection(
            icon = "👁",
            title = "Eye Strain & Visual Fatigue",
            description = "Prolonged VR use can cause eye strain, dryness, and visual fatigue. Rest your eyes for 10-15 minutes every 30 minutes of use. Avoid using VR if you are tired or lack sleep."
        ))

        contentLayout.addView(createWarningSection(
            icon = "🛡",
            title = "Safe Environment & Surroundings",
            description = "Always ensure you are in a clear, safe physical space free of obstacles, stairs, pets, or furniture before putting on the headset."
        ))

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))
            addView(contentLayout)
        }
        card.addView(scrollView)

        // M3 Acceptance Button
        val acceptBtn = MaterialTheme.filledButton(context, "I Understand & Agree") {
            onAccept()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(16) }
        }
        card.addView(acceptBtn)

        addView(card)
    }

    private fun createWarningSection(icon: String, title: String, description: String): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainerLow)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), MaterialTheme.OutlineVariant)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(TextView(context).apply {
            text = icon
            textSize = 16f
            setPadding(0, 0, dp(10), 0)
        })

        titleRow.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(MaterialTheme.OnSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })

        container.addView(titleRow)

        container.addView(TextView(context).apply {
            text = description
            textSize = 12f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            setPadding(0, dp(6), 0, 0)
        })

        return container
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
