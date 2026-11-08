package com.dreamshell.vr

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dreamshell.vr.controller.DaydreamController
import com.dreamshell.vr.controller.PointerMapper
import kotlin.math.roundToInt

/**
 * Launcher shown outside the headset: connect the controller, pick what to watch,
 * select and edit homepage apps, and tune lens/comfort settings.
 * Built in code so the project has no XML or AndroidX.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_VIDEO = 2
        val ACCENT = MaterialTheme.Primary
        val MUTED = MaterialTheme.OnSurfaceVariant
    }

    private lateinit var mainScroll: ScrollView
    private lateinit var appsCard: View
    private lateinit var homepageAppsBox: LinearLayout
    private var homepageApps: MutableList<HomepageApp> = mutableListOf()

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var testView: ControllerTestView
    private lateinit var logText: TextView
    private lateinit var settingsBox: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var videoUrlField: EditText
    private lateinit var sbsSwitch: Switch
    private var settings = VrSettings()

    private val statusListener = DaydreamController.StatusListener { updateStatus(it) }
    private val logListener = DaydreamController.LogListener { lines ->
        logText.text = lines.takeLast(25).joinToString("\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VrSettings.load(this)
        homepageApps = HomepageApp.loadApps(this).toMutableList()
        window.decorView.setBackgroundColor(MaterialTheme.Surface)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        mainScroll = ScrollView(this).apply { addView(col); isFillViewport = true }
        setContentView(mainScroll)
        mainScroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        col.addView(text("DreamShell", 28f, Color.WHITE, bold = true))
        col.addView(text("Browser and video player for the Daydream View headset", 14f, MUTED).apply {
            setPadding(0, dp(2), 0, dp(16))
        })

        col.isLongClickable = true
        col.setOnLongClickListener { openHomepageMenuInVr(); true }
        mainScroll.isLongClickable = true
        mainScroll.setOnLongClickListener { openHomepageMenuInVr(); true }

        // Controller
        col.addView(card {
            addView(heading("Motion controller"))
            statusText = text("", 15f, Color.WHITE)
            addView(statusText)
            addView(text("Hold the Home button on the controller to wake it, then connect. " +
                "The first time, Android asks to pair: tap Pair.", 13f, MUTED))
            connectButton = button("Connect") { onConnectClicked() }
            addView(connectButton)
            addView(button("Re-pair controller") {
                if (!DaydreamController.hasPermissions(this@MainActivity)) {
                    requestPermissions(DaydreamController.requiredPermissions, REQ_PERMS)
                } else if (!DaydreamController.repair(this@MainActivity)) {
                    Toast.makeText(this@MainActivity,
                        "Tap \"Daydream controller\" → Forget, then come back and tap Connect",
                        Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }.apply { secondary() })
            addView(text("Live test: point, touch and press buttons here before putting the headset on.",
                13f, MUTED).apply { setPadding(0, dp(14), 0, dp(6)) })
            testView = ControllerTestView(this@MainActivity)
            settings.applyTo(testView.pointer)
            addView(testView)
            logText = text("", 11f, MUTED).apply {
                typeface = Typeface.MONOSPACE
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
                setTextIsSelectable(true)
            }
            addView(toggle("Show connection log", false) { v ->
                logText.visibility = if (v) View.VISIBLE else View.GONE
            })
            addView(logText)
        })

        // Homepage Menu Apps
        appsCard = card {
            addView(heading("Homepage Menu Apps"))
            addView(text("In the stereo viewer, apps appear purely as app icons. Select which apps are displayed on the homepage, edit their properties, reorder them, or add new apps from your phone or web:", 13f, MUTED).apply {
                setPadding(0, 0, 0, dp(10))
            })

            homepageAppsBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(homepageAppsBox)

            val btnRow1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            btnRow1.addView(button("+ Add Custom App") {
                showEditAppDialog(null, -1)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) }
            })
            btnRow1.addView(button("+ Add Installed App") {
                showPickInstalledAppDialog()
            }.apply {
                secondary()
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) }
            })
            addView(btnRow1)

            val btnRow2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            btnRow2.addView(button("Preview on Screen") {
                showHomepageMenuDialog()
            }.apply {
                secondary()
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) }
            })
            btnRow2.addView(button("Open in VR Headset") {
                openHomepageMenuInVr()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) }
            })
            addView(btnRow2)

            addView(button("Reset Apps to Defaults") {
                homepageApps = HomepageApp.resetToDefaults(this@MainActivity).toMutableList()
                refreshHomepageAppsList()
                Toast.makeText(this@MainActivity, "Apps reset to defaults", Toast.LENGTH_SHORT).show()
            }.apply {
                secondary()
            })
        }
        col.addView(appsCard)
        refreshHomepageAppsList()

        // Browser
        col.addView(card {
            addView(heading("Browser"))
            urlField = field("https://www.youtube.com", InputType.TYPE_TEXT_VARIATION_URI)
            urlField.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { openBrowser(urlField.text.toString()); true } else false
            }
            urlField.imeOptions = EditorInfo.IME_ACTION_GO
            addView(urlField)
            addView(chips(listOf(
                "YouTube" to "https://www.youtube.com",
                "Google" to "https://www.google.com",
                "Wikipedia" to "https://en.wikipedia.org",
                "Vimeo" to "https://vimeo.com",
            )))
            addView(button("Open in headset") { openBrowser(urlField.text.toString()) })
        })

        // Video
        col.addView(card {
            addView(heading("Video player"))
            sbsSwitch = Switch(this@MainActivity).apply {
                text = "Video is side-by-side 3D"
                setTextColor(Color.WHITE)
                setPadding(0, dp(6), 0, dp(6))
            }
            addView(sbsSwitch)
            addView(button("Choose a video file…") { pickVideo() })
            videoUrlField = field("https://… .mp4 or .m3u8 stream", InputType.TYPE_TEXT_VARIATION_URI)
            addView(videoUrlField)
            addView(button("Play video URL") {
                val u = videoUrlField.text.toString().trim()
                if (u.isNotEmpty()) openVideo(Uri.parse(u))
            })
        })

        // Settings
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card {
            addView(heading("Headset & controller settings"))
            addView(button("Test lenses in headset") {
                startActivity(Intent(this@MainActivity, VrActivity::class.java)
                    .putExtra(VrActivity.EXTRA_MODE, VrActivity.MODE_CALIBRATE))
            })
            addView(settingsBox)
            addView(button("Reset everything to defaults") {
                settings = VrSettings(); settings.save(this@MainActivity); buildSettings()
            }.apply { secondary() })
        })
        buildSettings()

        col.addView(text(
            "In the headset: point and click the touchpad to tap, swipe to scroll, " +
                "App button = back, Home = centre the screen and pointer, volume buttons = volume.",
            13f, MUTED).apply { setPadding(dp(4), dp(12), dp(4), 0) })
    }

    override fun onResume() {
        super.onResume()
        DaydreamController.addStatusListener(statusListener)
        DaydreamController.addLogListener(logListener)
        homepageApps = HomepageApp.loadApps(this).toMutableList()
        refreshHomepageAppsList()
    }

    override fun onPause() {
        DaydreamController.removeStatusListener(statusListener)
        DaydreamController.removeLogListener(logListener)
        super.onPause()
    }

    // ---- Homepage Apps Management ---------------------------------------------------------

    private fun refreshHomepageAppsList() {
        homepageAppsBox.removeAllViews()
        if (homepageApps.isEmpty()) {
            homepageAppsBox.addView(text("No apps configured. Tap '+ Add Custom App' or 'Reset Apps to Defaults'.", 13f, MUTED).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        for (i in homepageApps.indices) {
            val app = homepageApps[i]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(MaterialTheme.SurfaceContainerLow)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), MaterialTheme.OutlineVariant)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            // Checkbox / switch: user selectable (controls visibility on the homepage)
            val toggle = Switch(this).apply {
                isChecked = app.isEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    homepageApps[i] = app.copy(isEnabled = isChecked)
                    HomepageApp.saveApps(this@MainActivity, homepageApps)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
            }
            row.addView(toggle)

            // Standard Android app icon preview from device
            val iconView = ImageView(this).apply {
                setImageDrawable(app.getIconDrawable(this@MainActivity))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(10)
                }
            }
            row.addView(iconView)

            // Text Info column
            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(text(app.name, 14f, MaterialTheme.OnSurface, bold = true))
            if (app.preferNative) {
                titleRow.addView(text(" · Native", 11f, MaterialTheme.Primary).apply {
                    setPadding(dp(4), 0, 0, 0)
                })
            } else {
                titleRow.addView(text(" · VR Browser", 11f, MaterialTheme.OnSurfaceVariant).apply {
                    setPadding(dp(4), 0, 0, 0)
                })
            }
            infoCol.addView(titleRow)

            val sub = if (app.packageName.isNotEmpty()) app.packageName else app.url
            infoCol.addView(text(sub, 11f, MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(infoCol)

            // Up / Down reorder buttons
            val moveUp = TextView(this).apply {
                text = "▲"
                textSize = 12f
                setTextColor(if (i > 0) MaterialTheme.OnSurface else MaterialTheme.OutlineVariant)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    if (i > 0) {
                        val temp = homepageApps[i]
                        homepageApps[i] = homepageApps[i - 1]
                        homepageApps[i - 1] = temp
                        HomepageApp.saveApps(this@MainActivity, homepageApps)
                        refreshHomepageAppsList()
                    }
                }
            }
            row.addView(moveUp)

            val moveDown = TextView(this).apply {
                text = "▼"
                textSize = 12f
                setTextColor(if (i < homepageApps.size - 1) MaterialTheme.OnSurface else MaterialTheme.OutlineVariant)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    if (i < homepageApps.size - 1) {
                        val temp = homepageApps[i]
                        homepageApps[i] = homepageApps[i + 1]
                        homepageApps[i + 1] = temp
                        HomepageApp.saveApps(this@MainActivity, homepageApps)
                        refreshHomepageAppsList()
                    }
                }
            }
            row.addView(moveDown)

            // Edit button (editable)
            val editBtn = TextView(this).apply {
                text = "✎"
                textSize = 15f
                setTextColor(MaterialTheme.Primary)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    showEditAppDialog(app, i)
                }
            }
            row.addView(editBtn)

            // Delete button
            val deleteBtn = TextView(this).apply {
                text = "✕"
                textSize = 14f
                setTextColor(MaterialTheme.ErrorContainer)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val removed = homepageApps.removeAt(i)
                    HomepageApp.saveApps(this@MainActivity, homepageApps)
                    refreshHomepageAppsList()
                    Toast.makeText(this@MainActivity, "Removed ${removed.name}", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(deleteBtn)

            homepageAppsBox.addView(row)
        }
    }

    private fun showEditAppDialog(app: HomepageApp?, index: Int) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainer)
                cornerRadius = dp(20).toFloat()
            }
        }

        val title = text(if (app == null) "Add Homepage App" else "Edit ${app.name}", 18f, MaterialTheme.OnSurface, bold = true)
        container.addView(title)

        container.addView(text("App Name", 12f, MUTED).apply { setPadding(0, dp(12), 0, dp(4)) })
        val nameField = field("e.g. YouTube", InputType.TYPE_CLASS_TEXT).apply {
            setText(app?.name ?: "")
        }
        container.addView(nameField)

        container.addView(text("Web URL", 12f, MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })
        val urlField = field("e.g. https://www.youtube.com", InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(app?.url ?: "https://")
        }
        container.addView(urlField)

        container.addView(text("Android Package Name (optional)", 12f, MUTED).apply { setPadding(0, dp(10), 0, dp(4)) })
        val pkgField = field("e.g. com.google.android.youtube", InputType.TYPE_CLASS_TEXT).apply {
            setText(app?.packageName ?: "")
        }
        container.addView(pkgField)

        val symbolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val symbolCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        }
        symbolCol.addView(text("Badge Symbol / Emoji", 12f, MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        val symbolField = field("e.g. ▶, 🌐, ✦", InputType.TYPE_CLASS_TEXT).apply {
            setText(app?.badgeSymbol ?: "✦")
        }
        symbolCol.addView(symbolField)
        symbolRow.addView(symbolCol)

        val colorCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) }
        }
        colorCol.addView(text("Badge Color Hex", 12f, MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        val colorField = field("#4285F4", InputType.TYPE_CLASS_TEXT).apply {
            setText(app?.badgeColorHex ?: "#4285F4")
        }
        colorCol.addView(colorField)
        symbolRow.addView(colorCol)
        container.addView(symbolRow)

        // Palette chips
        val palette = listOf(
            "#FF0000", "#4285F4", "#34A853", "#FBBC05",
            "#FF9900", "#8E24AA", "#00BCD4", "#E91E63", "#607D8B"
        )
        val paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
        }
        for (hex in palette) {
            val dot = View(this).apply {
                val parsed = runCatching { Color.parseColor(hex) }.getOrDefault(Color.BLUE)
                background = GradientDrawable().apply {
                    setColor(parsed)
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) }
                setOnClickListener {
                    colorField.setText(hex)
                }
            }
            paletteRow.addView(dot)
        }
        container.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(paletteRow)
        })

        // Prefer Native switch
        val preferNativeSwitch = Switch(this).apply {
            text = "Prefer Native App if installed (otherwise VR Browser)"
            isChecked = app?.preferNative ?: false
            setTextColor(MaterialTheme.OnSurface)
            setPadding(0, dp(6), 0, dp(6))
        }
        container.addView(preferNativeSwitch)

        // Enabled switch
        val enabledSwitch = Switch(this).apply {
            text = "Display on homepage in stereo viewer"
            isChecked = app?.isEnabled ?: true
            setTextColor(MaterialTheme.OnSurface)
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(enabledSwitch)

        // Action buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        val cancelBtn = MaterialTheme.outlinedButton(this, "Cancel") {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                rightMargin = dp(8)
            }
        }
        btnRow.addView(cancelBtn)

        val saveBtn = MaterialTheme.filledButton(this, "Save App") {
            val name = nameField.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter an app name", Toast.LENGTH_SHORT).show()
                return@filledButton
            }
            var url = urlField.text.toString().trim()
            if (url.isNotEmpty() && !url.contains("://")) {
                url = "https://$url"
            }
            val pkg = pkgField.text.toString().trim()
            val sym = symbolField.text.toString().trim().ifEmpty { name.take(1).uppercase() }
            val col = colorField.text.toString().trim().ifEmpty { "#4285F4" }
            val updated = HomepageApp(
                id = app?.id ?: ("app_" + System.currentTimeMillis()),
                name = name,
                url = url.ifEmpty { "https://www.google.com" },
                packageName = pkg,
                description = app?.description ?: name,
                badgeSymbol = sym,
                badgeColorHex = if (col.startsWith("#")) col else "#$col",
                isEnabled = enabledSwitch.isChecked,
                preferNative = preferNativeSwitch.isChecked
            )
            if (index >= 0 && index < homepageApps.size) {
                homepageApps[index] = updated
            } else {
                homepageApps.add(updated)
            }
            HomepageApp.saveApps(this@MainActivity, homepageApps)
            refreshHomepageAppsList()
            dialog.dismiss()
            Toast.makeText(this@MainActivity, "Saved $name", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        }
        btnRow.addView(saveBtn)

        container.addView(btnRow)

        val scroll = ScrollView(this).apply { addView(container) }
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun showPickInstalledAppDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        if (resolveList.isEmpty()) {
            Toast.makeText(this, "No launchable apps found on device", Toast.LENGTH_SHORT).show()
            return
        }

        val sorted = resolveList.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(MaterialTheme.SurfaceContainer)
                cornerRadius = dp(20).toFloat()
            }
        }

        root.addView(text("Select App from Device", 18f, MaterialTheme.OnSurface, bold = true))
        root.addView(text("Tap any installed app to add it to the VR homepage menu:", 12f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (info in sorted) {
            val appLabel = info.loadLabel(pm).toString()
            val pkgName = info.activityInfo.packageName
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(MaterialTheme.SurfaceContainerLow)
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                isClickable = true
                setOnClickListener {
                    val newApp = HomepageApp(
                        id = "app_" + System.currentTimeMillis() + "_" + pkgName.hashCode(),
                        name = appLabel,
                        url = "https://www.google.com/search?q=" + Uri.encode(appLabel),
                        packageName = pkgName,
                        description = appLabel,
                        badgeSymbol = appLabel.take(1).uppercase(),
                        badgeColorHex = "#4285F4",
                        isEnabled = true,
                        preferNative = true
                    )
                    homepageApps.add(newApp)
                    HomepageApp.saveApps(this@MainActivity, homepageApps)
                    refreshHomepageAppsList()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Added $appLabel to homepage menu", Toast.LENGTH_SHORT).show()
                }
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(info.loadIcon(pm))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
            }
            item.addView(iconView)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(text(appLabel, 14f, MaterialTheme.OnSurface, bold = true))
            textCol.addView(text(pkgName, 11f, MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            item.addView(textCol)
            listLayout.addView(item)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
            addView(listLayout)
        }
        root.addView(scroll)

        val closeBtn = MaterialTheme.outlinedButton(this, "Close") {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(12)
            }
        }
        root.addView(closeBtn)

        dialog.setContentView(root)
        dialog.show()
    }

    // ---- Actions --------------------------------------------------------------------------

    private fun onConnectClicked() {
        if (isLinked(DaydreamController.status)) {
            DaydreamController.disconnect(); return
        }
        if (!DaydreamController.hasPermissions(this)) {
            requestPermissions(DaydreamController.requiredPermissions, REQ_PERMS)
        } else {
            DaydreamController.connect(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && DaydreamController.hasPermissions(this)) {
            DaydreamController.connect(this)
        } else if (requestCode == REQ_PERMS) {
            statusText.text = "Bluetooth permission is needed to talk to the controller."
        }
    }

    private fun isLinked(s: DaydreamController.Status) = s in setOf(
        DaydreamController.Status.STREAMING, DaydreamController.Status.CONNECTED,
        DaydreamController.Status.SUBSCRIBING, DaydreamController.Status.PAIRING)

    private fun updateStatus(s: DaydreamController.Status) {
        statusText.text = when (s) {
            DaydreamController.Status.STREAMING -> "● Connected and receiving data"
            DaydreamController.Status.CONNECTED -> "Connected, waiting for data… press Home on the controller"
            DaydreamController.Status.SUBSCRIBING -> "Setting up…"
            DaydreamController.Status.PAIRING -> "Pairing… tap Pair if Android asks"
            DaydreamController.Status.CONNECTING -> "Connecting…"
            DaydreamController.Status.SCANNING -> "Searching… press and hold Home on the controller"
            DaydreamController.Status.NOT_FOUND -> "Controller not found. Wake it and try again."
            DaydreamController.Status.BLUETOOTH_OFF -> "Bluetooth is off. Turn it on and try again."
            DaydreamController.Status.DISCONNECTED -> "Not connected"
        }
        statusText.setTextColor(if (s == DaydreamController.Status.STREAMING) ACCENT else Color.WHITE)
        connectButton.text = if (isLinked(s)) "Disconnect" else "Connect"
        connectButton.isEnabled = s != DaydreamController.Status.SCANNING && s != DaydreamController.Status.CONNECTING
    }

    private fun openBrowser(raw: String) {
        var url = raw.trim().ifEmpty { "https://www.youtube.com" }
        if (!url.contains("://")) {
            url = if (url.contains('.') && !url.contains(' ')) "https://$url"
            else "https://www.google.com/search?q=" + Uri.encode(url)
        }
        startActivity(Intent(this, VrActivity::class.java)
            .putExtra(VrActivity.EXTRA_MODE, VrActivity.MODE_BROWSER)
            .putExtra(VrActivity.EXTRA_URL, url))
    }

    private fun openHomepageMenuInVr() {
        startActivity(Intent(this, VrActivity::class.java)
            .putExtra(VrActivity.EXTRA_MODE, VrActivity.MODE_BROWSER)
            .putExtra(VrActivity.EXTRA_SHOW_MENU, true))
    }

    private fun showHomepageMenuDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val menuView = HomepageMenuView(
            context = this,
            onOpenVrApp = { app ->
                dialog.dismiss()
                openBrowser(app.url)
            },
            onLaunchNativeApp = { app ->
                dialog.dismiss()
                if (!app.launchNativeApp(this)) {
                    openBrowser(app.url)
                }
            },
            onOpenSettings = {
                dialog.dismiss()
            },
            onOpenDisclaimer = {
                dialog.dismiss()
                showDisclaimerDialog()
            },
            onDismiss = {
                dialog.dismiss()
            }
        )
        dialog.setContentView(menuView)
        dialog.show()
    }

    private fun showDisclaimerDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val view = SafetyDisclaimerView(
            context = this,
            onAccept = {
                settings = settings.copy(disclaimerAccepted = true)
                settings.save(this)
                dialog.dismiss()
            }
        )
        dialog.setContentView(view)
        dialog.show()
    }

    private fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*"), REQ_VIDEO)
    }

    @Deprecated("Framework-only project; no AndroidX activity-result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VIDEO && resultCode == RESULT_OK) data?.data?.let { openVideo(it) }
    }

    private fun openVideo(uri: Uri) {
        startActivity(Intent(this, VrActivity::class.java)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(VrActivity.EXTRA_MODE, VrActivity.MODE_VIDEO)
            .putExtra(VrActivity.EXTRA_SBS, sbsSwitch.isChecked))
    }

    // ---- Settings UI ----------------------------------------------------------------------

    private fun buildSettings() {
        settingsBox.removeAllViews()
        val s = settings
        fun upd(f: (VrSettings) -> VrSettings) {
            settings = f(settings)
            settings.save(this)
            settings.applyTo(testView.pointer)
        }
        fun section(t: String) = settingsBox.addView(text(t, 15f, ACCENT, bold = true).apply {
            setPadding(0, dp(16), 0, dp(2))
        })

        section("Homepage Menu")
        settingsBox.addView(text("Configure which apps are visible as icons on the VR homepage, edit them, or add new ones:", 12f, MUTED))
        settingsBox.addView(button("Customize Homepage Apps…") {
            mainScroll.smoothScrollTo(0, appsCard.top)
        }.apply { secondary() })

        section("Lenses")
        settingsBox.addView(choices("Headset", ViewerPreset.entries.map { it.label to it }, s.viewer) { p ->
            upd { it.withViewer(p) }; buildSettings()
        })
        settingsBox.addView(text(
            "Field of view: " + run {
                val dm = resources.displayMetrics
                val wM = maxOf(dm.widthPixels, dm.heightPixels) / dm.xdpi * 0.0254f
                val hM = minOf(dm.widthPixels, dm.heightPixels) / dm.ydpi * 0.0254f
                val f = com.dreamshell.vr.gl.LensModel(s, wM, hM).fovDeg(0)
                "outer %.0f°, inner %.0f°, down %.0f°, up %.0f° per eye".format(f[0], f[1], f[2], f[3])
            }, 12f, MUTED))
        settingsBox.addView(toggle("Lens distortion correction", s.distortionEnabled) { v -> upd { it.copy(distortionEnabled = v) } })
        settingsBox.addView(slider("Lens separation (also eye separation)", 55f, 75f, 0.1f, s.interLensMm, "%.1f mm") { v -> upd { it.copy(interLensMm = v) } })
        settingsBox.addView(slider("Screen to lens", 30f, 50f, 0.1f, s.screenToLensMm, "%.1f mm") { v -> upd { it.copy(screenToLensMm = v) } })
        settingsBox.addView(choices("Phone sits", listOf(
            "Centred" to VerticalAlignment.CENTER, "On bottom edge" to VerticalAlignment.BOTTOM,
            "On top edge" to VerticalAlignment.TOP), s.verticalAlignment) { a ->
            upd { it.copy(verticalAlignment = a) }; buildSettings()
        })
        if (s.verticalAlignment != VerticalAlignment.CENTER) {
            settingsBox.addView(slider("Edge to lens centre", 20f, 50f, 0.5f, s.trayToLensMm, "%.1f mm") { v -> upd { it.copy(trayToLensMm = v) } })
        }
        settingsBox.addView(slider("Lens height nudge", -10f, 10f, 0.5f, s.lensOffsetYMm, "%.1f mm") { v -> upd { it.copy(lensOffsetYMm = v) } })
        settingsBox.addView(slider("Distortion k1", 0f, 1f, 0.0005f, s.k1, "%.4f") { v -> upd { it.copy(k1 = v) } })
        settingsBox.addView(slider("Distortion k2", -0.5f, 1.5f, 0.0005f, s.k2, "%.4f") { v -> upd { it.copy(k2 = v) } })
        settingsBox.addView(slider("Distortion k3", -0.5f, 2f, 0.0005f, s.k3, "%.4f") { v -> upd { it.copy(k3 = v) } })
        settingsBox.addView(slider("Lens field-of-view limit", 30f, 75f, 1f, s.maxFovDeg, "%.0f°") { v -> upd { it.copy(maxFovDeg = v) } })
        settingsBox.addView(toggle("Image upside down? Flip landscape", s.reverseLandscape) { v -> upd { it.copy(reverseLandscape = v) } })

        section("Controller")
        settingsBox.addView(toggle("Point with controller (off = touchpad moves cursor)", s.airPointer) { v -> upd { it.copy(airPointer = v) } })
        settingsBox.addView(slider("Touchpad sensitivity", 25f, 150f, 5f, s.touchpadSensitivity * 100f, "%.0f%%") { v -> upd { it.copy(touchpadSensitivity = v / 100f) } })
        settingsBox.addView(choices("Pointing axis", listOf(
            "Z (default)" to PointerMapper.Axis.Z, "Y" to PointerMapper.Axis.Y, "X" to PointerMapper.Axis.X),
            s.pointerAxis) { a -> upd { it.copy(pointerAxis = a) }; buildSettings() })
        settingsBox.addView(toggle("Invert pointer left/right", s.pointerInvertX) { v -> upd { it.copy(pointerInvertX = v) } })
        settingsBox.addView(toggle("Invert pointer up/down", s.pointerInvertY) { v -> upd { it.copy(pointerInvertY = v) } })
        settingsBox.addView(toggle("Invert touchpad scrolling", s.invertScroll) { v -> upd { it.copy(invertScroll = v) } })

        section("Comfort")
        settingsBox.addView(slider("Screen size", 30f, 100f, 1f, s.screenWidthDeg, "%.0f°") { v -> upd { it.copy(screenWidthDeg = v) } })
        settingsBox.addView(slider("Screen distance", 1f, 6f, 0.1f, s.screenDistanceM, "%.1f m") { v -> upd { it.copy(screenDistanceM = v) } })
    }

    // ---- Tiny view helpers ----------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun text(t: String, sizeSp: Float, color: Int = MaterialTheme.OnSurfaceVariant, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun heading(t: String) = text(t, 18f, MaterialTheme.OnSurface, bold = true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply {
            setColor(MaterialTheme.SurfaceContainer)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), MaterialTheme.OutlineVariant)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) }
        build()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(MaterialTheme.OnPrimary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(MaterialTheme.Primary)
            cornerRadius = dp(100).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun Button.secondary() {
        setTextColor(MaterialTheme.Primary)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), MaterialTheme.Outline)
            cornerRadius = dp(100).toFloat()
        }
    }

    private fun field(hint: String, variation: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(MaterialTheme.OnSurfaceVariant)
        setTextColor(MaterialTheme.OnSurface)
        textSize = 14f
        setSingleLine()
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(MaterialTheme.SurfaceContainerLow)
            setStroke(dp(1), MaterialTheme.Outline)
            cornerRadius = dp(14).toFloat()
        }
        inputType = InputType.TYPE_CLASS_TEXT or variation
        if (hint.startsWith("https://www")) setText(hint)
    }

    private fun chips(items: List<Pair<String, String>>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, url) in items) {
            row.addView(TextView(this).apply {
                text = label
                setTextColor(MaterialTheme.OnSurface)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(MaterialTheme.SurfaceContainerHigh)
                    setStroke(dp(1), MaterialTheme.OutlineVariant)
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setOnClickListener { urlField.setText(url); openBrowser(url) }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            setPadding(0, dp(10), 0, 0)
        }
    }

    /** A labelled row of pill buttons; tapping one selects it. */
    private fun <T> choices(label: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        box.addView(text(label, 14f, MaterialTheme.OnSurface))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        for ((name, value) in options) {
            val on = value == selected
            row.addView(TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(if (on) MaterialTheme.OnPrimaryContainer else MaterialTheme.OnSurfaceVariant)
                typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(if (on) MaterialTheme.PrimaryContainer else MaterialTheme.SurfaceContainerHigh)
                    setStroke(dp(1), if (on) MaterialTheme.Primary else MaterialTheme.OutlineVariant)
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setOnClickListener { if (!on) onPick(value) }
            })
        }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return box
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = value
        setTextColor(MaterialTheme.OnSurface)
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
    }

    private fun slider(label: String, min: Float, max: Float, step: Float, value: Float, fmt: String,
                       onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val title = text("", 14f, MaterialTheme.OnSurface)
        fun show(v: Float) { title.text = "$label  ·  ${String.format(fmt, v)}" }
        show(value)
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((value - min) / step).roundToInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    show(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        box.addView(title)
        box.addView(bar)
        return box
    }
}
