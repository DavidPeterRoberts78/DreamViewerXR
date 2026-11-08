package com.dreamshell.vr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomepageAppTest {

    @Test
    fun defaultAppsCountAndDefaults() {
        val apps = HomepageApp.DEFAULT_APPS
        assertEquals(7, apps.size)
        assertTrue(apps.all { it.isEnabled })
        assertTrue(apps.any { it.id == "youtube" && it.name == "YouTube" })
        assertTrue(apps.any { it.id == "chrome" && it.name == "Google Chrome" })
    }

    @Test
    fun serializationRoundTrip() {
        val original = HomepageApp(
            id = "test_app_1",
            name = "Test App",
            url = "https://example.com/app",
            packageName = "com.example.app",
            description = "A test app",
            badgeSymbol = "🎮",
            badgeColorHex = "#123456",
            isEnabled = false,
            preferNative = true
        )

        val json = original.toJson()
        val parsed = HomepageApp.fromJson(json)

        assertEquals("test_app_1", parsed.id)
        assertEquals("Test App", parsed.name)
        assertEquals("https://example.com/app", parsed.url)
        assertEquals("com.example.app", parsed.packageName)
        assertEquals("A test app", parsed.description)
        assertEquals("🎮", parsed.badgeSymbol)
        assertEquals("#123456", parsed.badgeColorHex)
        assertFalse(parsed.isEnabled)
        assertTrue(parsed.preferNative)
    }

    @Test
    fun fromJsonWithMissingFieldsUsesDefaults() {
        val json = JSONObject()
        json.put("name", "Minimal App")
        json.put("url", "https://example.com")

        val parsed = HomepageApp.fromJson(json)
        assertEquals("Minimal App", parsed.name)
        assertEquals("https://example.com", parsed.url)
        assertTrue(parsed.isEnabled)
        assertFalse(parsed.preferNative)
        assertEquals("✦", parsed.badgeSymbol)
        assertEquals("#4285F4", parsed.badgeColorHex)
    }
}
