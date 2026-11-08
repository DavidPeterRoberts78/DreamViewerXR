package com.dreamshell.vr

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Apps accessible natively and via VR web browser in the DreamShell homepage menu.
 */
data class HomepageApp(
    val id: String,
    val name: String,
    val url: String,
    val packageName: String = "",
    val description: String = "",
    val badgeSymbol: String = "✦",
    val badgeColorHex: String = "#4285F4",
    val isEnabled: Boolean = true,
    val preferNative: Boolean = false
) {
    fun isInstalled(context: Context): Boolean {
        if (packageName.isBlank()) return false
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the standard Android app icon from the device.
     */
    fun getIconDrawable(context: Context): Drawable {
        val pm = context.packageManager

        // 1. If packageName is provided, query the device's launch activity icon or application icon
        if (packageName.isNotBlank()) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    return pm.getActivityIcon(launchIntent)
                }
            } catch (_: Exception) {}

            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                return pm.getApplicationIcon(appInfo)
            } catch (_: Exception) {}
        }

        // 2. Query all launcher activities on the device to find matching app by package or name
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val activities = pm.queryIntentActivities(mainIntent, 0)
            if (packageName.isNotBlank()) {
                val match = activities.firstOrNull { it.activityInfo.packageName.equals(packageName, ignoreCase = true) }
                if (match != null) {
                    return match.loadIcon(pm)
                }
            }
            if (name.isNotBlank()) {
                val match = activities.firstOrNull {
                    it.loadLabel(pm).toString().equals(name, ignoreCase = true)
                }
                if (match != null) {
                    return match.loadIcon(pm)
                }
            }
        } catch (_: Exception) {}

        // 3. If URL is provided, try resolving the device's handler (e.g. standard browser or content handler)
        if (url.isNotBlank() && url.contains("://")) {
            try {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val resolve = pm.resolveActivity(viewIntent, 0)
                if (resolve != null && resolve.activityInfo.packageName != "android" && resolve.activityInfo.packageName != context.packageName) {
                    return resolve.loadIcon(pm)
                }
            } catch (_: Exception) {}
        }

        // 4. Return the standard default Android app icon from the device
        return try {
            pm.defaultActivityIcon
        } catch (_: Exception) {
            context.getDrawable(android.R.drawable.sym_def_app_icon)
                ?: ColorDrawable(MaterialTheme.Primary)
        }
    }

    fun launchNativeApp(context: Context): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("url", url)
            put("packageName", packageName)
            put("description", description)
            put("badgeSymbol", badgeSymbol)
            put("badgeColorHex", badgeColorHex)
            put("isEnabled", isEnabled)
            put("preferNative", preferNative)
        }
    }

    companion object {
        private const val PREFS = "vr_homepage_apps"
        private const val KEY_APPS = "apps_json"

        val DEFAULT_APPS = listOf(
            HomepageApp(
                id = "youtube",
                name = "YouTube",
                url = "https://www.youtube.com",
                packageName = "com.google.android.youtube",
                description = "Watch videos and 360° VR content",
                badgeSymbol = "▶",
                badgeColorHex = "#FF0000",
                isEnabled = true,
                preferNative = false
            ),
            HomepageApp(
                id = "chrome",
                name = "Google Chrome",
                url = "https://www.google.com",
                packageName = "com.android.chrome",
                description = "Fast web search & browsing",
                badgeSymbol = "🌐",
                badgeColorHex = "#4285F4",
                isEnabled = true,
                preferNative = false
            ),
            HomepageApp(
                id = "maps",
                name = "Google Maps",
                url = "https://maps.google.com",
                packageName = "com.google.android.apps.maps",
                description = "Explore 3D maps & Street View",
                badgeSymbol = "📍",
                badgeColorHex = "#34A853",
                isEnabled = true,
                preferNative = false
            ),
            HomepageApp(
                id = "photos",
                name = "Google Photos",
                url = "https://photos.google.com",
                packageName = "com.google.android.apps.photos",
                description = "View photos and VR memories",
                badgeSymbol = "🖼",
                badgeColorHex = "#FBBC05",
                isEnabled = true,
                preferNative = false
            ),
            HomepageApp(
                id = "amazon",
                name = "Amazon Shopping",
                url = "https://www.amazon.com",
                packageName = "com.amazon.mShop.android.shopping",
                description = "Shop online & browse products",
                badgeSymbol = "🛒",
                badgeColorHex = "#FF9900",
                isEnabled = true,
                preferNative = false
            ),
            HomepageApp(
                id = "gemini",
                name = "Gemini Live",
                url = "https://gemini.google.com",
                packageName = "com.google.android.apps.bard",
                description = "AI voice assistant & live chat",
                badgeSymbol = "✦",
                badgeColorHex = "#8E24AA",
                isEnabled = true,
                preferNative = false
            ),
            HomepageApp(
                id = "polycam",
                name = "Polycam",
                url = "https://poly.cam",
                packageName = "ai.polycam",
                description = "3D capture, LiDAR & scanning",
                badgeSymbol = "🧊",
                badgeColorHex = "#00BCD4",
                isEnabled = true,
                preferNative = false
            )
        )

        // For backwards compatibility
        val APPS: List<HomepageApp> get() = DEFAULT_APPS

        fun fromJson(obj: JSONObject): HomepageApp {
            return HomepageApp(
                id = obj.optString("id", System.currentTimeMillis().toString()),
                name = obj.optString("name", "App"),
                url = obj.optString("url", "https://www.google.com"),
                packageName = obj.optString("packageName", ""),
                description = obj.optString("description", ""),
                badgeSymbol = obj.optString("badgeSymbol", "✦"),
                badgeColorHex = obj.optString("badgeColorHex", "#4285F4"),
                isEnabled = obj.optBoolean("isEnabled", true),
                preferNative = obj.optBoolean("preferNative", false)
            )
        }

        fun loadApps(context: Context): List<HomepageApp> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_APPS, null) ?: return DEFAULT_APPS
            return try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<HomepageApp>()
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
                if (list.isEmpty()) DEFAULT_APPS else list
            } catch (_: Exception) {
                DEFAULT_APPS
            }
        }

        fun saveApps(context: Context, apps: List<HomepageApp>) {
            val array = JSONArray()
            for (app in apps) {
                array.put(app.toJson())
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_APPS, array.toString())
                .apply()
        }

        fun getEnabledApps(context: Context): List<HomepageApp> {
            val all = loadApps(context)
            val enabled = all.filter { it.isEnabled }
            return if (enabled.isEmpty()) all else enabled
        }

        fun resetToDefaults(context: Context): List<HomepageApp> {
            saveApps(context, DEFAULT_APPS)
            return DEFAULT_APPS
        }
    }
}
