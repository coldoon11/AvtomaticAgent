package com.example.autonomousai

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class GestureOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var cursor: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        if (!canDraw()) return
        if (cursor == null) showCursor()
        if (bubble == null) showBubble()
    }

    private fun showCursor() {
        val size = dp(64)
        val view = CursorView(context)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() / 2 - size / 2
            y = screenHeight() / 2 - size / 2
        }
        windowManager.addView(view, params)
        cursor = view
        cursorParams = params
    }

    private fun showBubble() {
        val size = dp(64)
        val view = TextView(context).apply {
            text = "AI\nMOVE"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(45, 45, 52))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - size - dp(18)
            y = screenHeight() / 3
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - downX)).toInt().coerceIn(0, screenWidth() - size)
                    params.y = (startY + (event.rawY - downY)).toInt().coerceIn(0, screenHeight() - size)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downX) + abs(event.rawY - downY)
                    if (moved < dp(12)) {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            action = MainActivity.ACTION_OPEN_FROM_BUBBLE
                        }
                        context.startActivity(intent)
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    fun updateCursor(x: Float, y: Float, mode: GestureMode) {
        val view = cursor ?: return
        val params = cursorParams ?: return
        val size = params.width
        params.x = (x - size / 2f).toInt().coerceIn(-size / 2, screenWidth() - size / 2)
        params.y = (y - size / 2f).toInt().coerceIn(-size / 2, screenHeight() - size / 2)
        view.mode = mode
        view.invalidate()
        bubble?.text = "AI\n${mode.shortLabel}"
        windowManager.updateViewLayout(view, params)
    }

    fun remove() {
        cursor?.let { runCatching { windowManager.removeView(it) } }
        bubble?.let { runCatching { windowManager.removeView(it) } }
        cursor = null
        cursorParams = null
        bubble = null
        bubbleParams = null
    }

    fun screenWidth(): Int = screenSize().first
    fun screenHeight(): Int = screenSize().second

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private class CursorView(context: Context) : View(context) {
        var mode: GestureMode = GestureMode.MOVE
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 3f
            color = Color.WHITE
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = resources.displayMetrics.scaledDensity * 8.5f
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f - width * 0.06f
            val radius = width * 0.23f
            fill.color = when (mode) {
                GestureMode.MOVE -> Color.rgb(60, 170, 255)
                GestureMode.PINCH -> Color.rgb(255, 180, 50)
                GestureMode.DRAG -> Color.rgb(170, 90, 255)
                GestureMode.SCROLL -> Color.rgb(60, 210, 150)
                GestureMode.LONG_PRESS -> Color.rgb(255, 110, 80)
                GestureMode.PAUSED -> Color.rgb(120, 120, 130)
            }
            canvas.drawCircle(cx, cy, radius + width * 0.10f, outer)
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawText(mode.shortLabel, cx, height * 0.92f, label)
        }
    }
}
