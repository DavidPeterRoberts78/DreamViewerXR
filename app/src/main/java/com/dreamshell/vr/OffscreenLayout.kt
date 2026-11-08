package com.dreamshell.vr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout

/**
 * Hosts a normal View tree (here, a WebView) but paints it into a Surface instead
 * of the window, so the GL renderer can show it on the virtual screen.
 *
 * The children are laid out [layoutScale] times larger than the Surface and scaled
 * down when drawn. That gives web pages a desktop-like CSS width (about 1000 px)
 * instead of a phone-sized one.
 */
class OffscreenLayout(context: Context) : FrameLayout(context) {

    var targetSurface: Surface? = null
    var layoutScale = 1f

    private companion object {
        val BG_COLOR = Color.parseColor("#111318")
    }

    init {
        setWillNotDraw(false)
    }

    override fun draw(canvas: Canvas) {
        // Paint nothing into the window; everything goes to the Surface.
        val s = targetSurface ?: return
        if (!s.isValid) return
        val c = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w("OffscreenLayout", "lockHardwareCanvas failed", e)
            return
        }
        try {
            c.drawColor(BG_COLOR) // Use dark background to prevent white flickering
            c.save()
            c.scale(1f / layoutScale, 1f / layoutScale)
            super.draw(c)
            c.restore()
        } finally {
            s.unlockCanvasAndPost(c)
        }
    }
}
