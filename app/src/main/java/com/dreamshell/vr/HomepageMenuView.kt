package com.dreamshell.vr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Material Design 3 native overlay menu displayed on the homepage in the stereo viewer.
 * Displays user-selectable and editable apps purely as app icons in a clean VR launcher grid.
 */
class HomepageMenuView(
    context: Context,
    private val onOpenVrApp: (HomepageApp) -> Unit,
    private val onLaunchNativeApp: ((HomepageApp) -> Unit)? = null,
    private val onOpenSettings: (() -> Unit)? = null,
    private val onOpenDisclaimer: (() -> Unit)? = null,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private companion object {
        val OVERLAY_BG = Color.parseColor("#E60B0D12")
        const val GRID_COLUMNS = 4
    }

    init {
        setBackgroundColor(OVERLAY_BG)
        isClickable = true
        isFocusable = true
        setOnClickListener { onDismiss() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainer)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), MaterialTheme.OutlineVariant)
            }
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                topMargin = dp(20)
                bottomMargin = dp(20)
            }
        }

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val headerText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(context).apply {
            text = "DreamShell Apps"
            textSize = 20f
            setTextColor(MaterialTheme.OnSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        headerText.addView(TextView(context).apply {
            text = "Tap an icon to launch · Long-press for native app · Hold Home for menu"
            textSize = 12f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(headerText)

        if (onOpenDisclaimer != null) {
            val disclaimerBtn = TextView(context).apply {
                text = "⚠️ Safety"
                textSize = 13f
                setTextColor(MaterialTheme.OnSecondaryContainer)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    setColor(MaterialTheme.SecondaryContainer)
                    cornerRadius = dp(100).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener { onOpenDisclaimer.invoke() }
            }
            header.addView(disclaimerBtn)
        }

        if (onOpenSettings != null) {
            val settingsBtn = TextView(context).apply {
                text = "⚙ Settings"
                textSize = 13f
                setTextColor(MaterialTheme.OnSecondaryContainer)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    setColor(MaterialTheme.SecondaryContainer)
                    cornerRadius = dp(100).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener { onOpenSettings.invoke() }
            }
            header.addView(settingsBtn)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainerHigh)
                cornerRadius = dp(100).toFloat()
            }
            setOnClickListener { onDismiss() }
        }
        header.addView(closeBtn)
        card.addView(header)

        // Divider
        card.addView(View(context).apply {
            setBackgroundColor(MaterialTheme.OutlineVariant)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(16)
            }
        })

        // Load user-selected apps
        val apps = HomepageApp.getEnabledApps(context)

        if (apps.isEmpty()) {
            val emptyBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }
            emptyBox.addView(TextView(context).apply {
                text = "📱"
                textSize = 40f
                gravity = Gravity.CENTER
            })
            emptyBox.addView(TextView(context).apply {
                text = "No apps selected"
                textSize = 16f
                setTextColor(MaterialTheme.OnSurface)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(4))
            })
            emptyBox.addView(TextView(context).apply {
                text = "Select or add apps in DreamShell Settings"
                textSize = 13f
                setTextColor(MaterialTheme.OnSurfaceVariant)
            })
            if (onOpenSettings != null) {
                emptyBox.addView(MaterialTheme.tonalButton(context, "Open Settings") {
                    onOpenSettings.invoke()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
                    ).apply { topMargin = dp(16) }
                })
            }
            card.addView(emptyBox)
        } else {
            // App Icons Grid in ScrollView
            val appGridLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            for (rowApps in apps.chunked(GRID_COLUMNS)) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(14) }
                }

                for (app in rowApps) {
                    val iconView = createAppIcon(app)
                    row.addView(iconView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }

                // Balance row columns if fewer than GRID_COLUMNS
                repeat(GRID_COLUMNS - rowApps.size) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }

                appGridLayout.addView(row)
            }

            val scrollView = ScrollView(context).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380))
                addView(appGridLayout)
            }
            card.addView(scrollView)
        }

        // Footer Text
        card.addView(TextView(context).apply {
            text = "Customize apps in Settings · Tap outside or press App button to dismiss"
            textSize = 12f
            setTextColor(MaterialTheme.OnSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })

        addView(card)
    }

    /**
     * Renders each app only as an app icon with its name label underneath, suitable for VR stereo viewing.
     */
    private fun createAppIcon(app: HomepageApp): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                val pressed = GradientDrawable().apply {
                    setColor(MaterialTheme.SurfaceContainerHighest)
                    cornerRadius = dp(18).toFloat()
                }
                val focused = GradientDrawable().apply {
                    setColor(MaterialTheme.SurfaceContainerHigh)
                    setStroke(dp(2), MaterialTheme.Primary)
                    cornerRadius = dp(18).toFloat()
                }
                val normal = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(18).toFloat()
                }
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
        }

        // Standard Android App Icon from device
        val iconDrawable = app.getIconDrawable(context)
        val iconView = ImageView(context).apply {
            setImageDrawable(iconDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        container.addView(iconView)

        // App Name Label below the icon
        val label = TextView(context).apply {
            text = app.name
            textSize = 13f
            setTextColor(MaterialTheme.OnSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        container.addView(label)

        // Click action: launch according to preferred mode (or VR by default)
        container.setOnClickListener {
            if (app.preferNative && app.isInstalled(context) && onLaunchNativeApp != null) {
                onLaunchNativeApp.invoke(app)
            } else {
                onOpenVrApp(app)
            }
        }

        // Long click action: toggle alternative launch mode if native app is installed
        container.setOnLongClickListener {
            if (app.isInstalled(context) && onLaunchNativeApp != null) {
                if (app.preferNative) {
                    onOpenVrApp(app)
                } else {
                    onLaunchNativeApp.invoke(app)
                }
                true
            } else {
                false
            }
        }

        return container
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
