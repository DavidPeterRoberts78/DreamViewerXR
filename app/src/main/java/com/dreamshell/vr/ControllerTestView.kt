package com.dreamshell.vr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.dreamshell.vr.controller.ControllerState
import com.dreamshell.vr.controller.DaydreamController
import com.dreamshell.vr.controller.PointerMapper
import kotlin.math.min
import kotlin.math.tan

/**
 * Live view of what the controller sends, for checking it before the headset goes on:
 * a mini "screen" with the pointer dot (same maths as the headset), the touchpad, the
 * buttons and the packet rate. Home recentres the dot, as in the headset.
 */
class ControllerTestView(context: Context) : View(context) {

    val pointer = PointerMapper()
    private var state = ControllerState()
    private var prevHome = false
    private val density = resources.displayMetrics.density

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.parseColor("#4A5263")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#12151B") }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7AA7FF") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA3B2"); textSize = 12f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * density; textAlign = Paint.Align.CENTER
    }

    private val listener = DaydreamController.StateListener { s ->
        val q = s.orientationQuat()
        if (s.isHomeDown != prevHome) pointer.setHome(q) // press and release, like the headset
        prevHome = s.isHomeDown
        state = s
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DaydreamController.addStateListener(listener)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        DaydreamController.removeStateListener(listener)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.62f * 9f / 16f + 96 * density).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8 * density
        val streaming = DaydreamController.isStreaming

        // Mini screen: same 62-degree, 16:9 screen as the headset default.
        val sw = w * 0.62f
        val sh = sw * 9f / 16f
        val screen = RectF(pad, pad, pad + sw, pad + sh)
        c.drawRoundRect(screen, 6 * density, 6 * density, fill)
        c.drawRoundRect(screen, 6 * density, 6 * density, frame)
        if (streaming) {
            val wM = 2f * tan(Math.toRadians(31.0)).toFloat()
            val uv = pointer.screenUv(state.orientationQuat(), wM, wM * 9f / 16f, 1f)
            if (uv != null) {
                val x = screen.left + uv[0].coerceIn(-0.05f, 1.05f) * sw
                val y = screen.top + uv[1].coerceIn(-0.05f, 1.05f) * sh
                val p = if (state.isClickDown) accent else dot
                c.drawCircle(x, y, 6 * density, p)
            }
        } else {
            c.drawText("No data from controller", screen.centerX(), screen.centerY(), label)
        }
        c.drawText("Pointer (Home = centre)", screen.left, screen.bottom + 16 * density, text)

        // Touchpad.
        val padR = min(w - sw - 3 * pad, sh) / 2f
        val cx = w - pad - padR
        val cy = pad + padR
        c.drawCircle(cx, cy, padR, fill)
        c.drawCircle(cx, cy, padR, frame)
        if (streaming && state.isTouching) {
            c.drawCircle(cx + (state.touchX - 0.5f) * 2 * padR, cy + (state.touchY - 0.5f) * 2 * padR,
                5 * density, if (state.isClickDown) accent else dot)
        }
        c.drawText("Touchpad", cx - padR, pad + 2 * padR + 16 * density, text)

        // Buttons.
        val names = listOf("Click", "App", "Home", "Vol−", "Vol+")
        val downs = listOf(state.isClickDown, state.isAppDown, state.isHomeDown,
            state.isVolMinusDown, state.isVolPlusDown)
        val by = screen.bottom + 40 * density
        val bw = (w - 2 * pad) / names.size
        for (i in names.indices) {
            val r = RectF(pad + i * bw + 3 * density, by - 14 * density,
                pad + (i + 1) * bw - 3 * density, by + 10 * density)
            val on = streaming && downs[i]
            c.drawRoundRect(r, 12 * density, 12 * density, if (on) accent else fill)
            c.drawRoundRect(r, 12 * density, 12 * density, frame)
            c.drawText(names[i], r.centerX(), r.centerY() + 4 * density, label)
        }

        val rate = DaydreamController.packetsPerSecond
        c.drawText(
            if (streaming) "Receiving $rate packets/s  ·  ${DaydreamController.totalPackets} total"
            else "Status: ${DaydreamController.status.name.lowercase()}",
            pad, by + 32 * density, text)

        if (isAttachedToWindow) postInvalidateDelayed(250)
    }
}
