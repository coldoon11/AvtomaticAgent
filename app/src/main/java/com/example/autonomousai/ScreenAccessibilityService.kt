package com.example.autonomousai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot

class ScreenAccessibilityService : AccessibilityService() {
    private var lastRefresh = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == applicationContext.packageName) return
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 180) return
        lastRefresh = now
        refreshSnapshot(now)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instanceRef.get() === this) instanceRef.clear()
        super.onDestroy()
    }

    private fun refreshSnapshot(now: Long) {
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        if (roots.isEmpty()) return

        val lines = LinkedHashSet<String>()
        var packageName = ""
        var visited = 0
        for (root in roots) {
            if (packageName.isBlank()) packageName = root.packageName?.toString().orEmpty()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && visited < 450 && lines.size < 140) {
                val node = queue.removeFirst()
                visited++
                if (node.isVisibleToUser) {
                    if (node.isPassword) {
                        lines.add("[поле пароля скрыто]")
                    } else {
                        val text = node.text?.toString()?.trim().orEmpty()
                        val desc = node.contentDescription?.toString()?.trim().orEmpty()
                        if (text.isNotBlank()) lines.add(text.take(500))
                        if (desc.isNotBlank() && desc != text) lines.add(desc.take(500))
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }
        }

        latestSnapshot = ScreenSnapshot(
            packageName = packageName,
            text = lines.joinToString("\n").take(MAX_CONTEXT_CHARS),
            updatedAtMillis = now,
        )
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 65)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val duration = ViewConfiguration.getLongPressTimeout().toLong() + 180L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun scroll(x: Float, y: Float, deltaY: Float): Boolean {
        val distance = deltaY.coerceIn(-650f, 650f)
        if (abs(distance) < 8f) return false
        val height = resources.displayMetrics.heightPixels.toFloat()
        val width = resources.displayMetrics.widthPixels.toFloat()
        val sx = x.coerceIn(24f, width - 24f)
        val sy = y.coerceIn(120f, height - 120f)
        val ey = (sy + distance).coerceIn(80f, height - 80f)
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(sx, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun drag(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val sx = startX.coerceIn(1f, width - 1f)
        val sy = startY.coerceIn(1f, height - 1f)
        val ex = endX.coerceIn(1f, width - 1f)
        val ey = endY.coerceIn(1f, height - 1f)
        val distance = hypot(ex - sx, ey - sy)
        if (distance < 12f) return longPress(sx, sy)

        val holdPath = Path().apply { moveTo(sx, sy) }
        val holdStroke = GestureDescription.StrokeDescription(
            holdPath,
            0,
            ViewConfiguration.getLongPressTimeout().toLong() + 80L,
            true,
        )
        val first = GestureDescription.Builder().addStroke(holdStroke).build()
        return dispatchGesture(first, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val movePath = Path().apply {
                    moveTo(sx, sy)
                    lineTo(ex, ey)
                }
                val moveDuration = (260L + distance.toLong() / 3L).coerceIn(280L, 780L)
                val continuation = runCatching {
                    holdStroke.continueStroke(movePath, 0, moveDuration, false)
                }.getOrNull() ?: return
                dispatchGesture(
                    GestureDescription.Builder().addStroke(continuation).build(),
                    null,
                    null,
                )
            }
        }, null)
    }

    companion object {
        private const val MAX_CONTEXT_CHARS = 12_000

        @Volatile
        private var latestSnapshot = ScreenSnapshot()

        @Volatile
        private var instanceRef = WeakReference<ScreenAccessibilityService>(null)

        fun snapshot(): ScreenSnapshot = latestSnapshot
        fun isConnected(): Boolean = instanceRef.get() != null
        fun performTap(x: Float, y: Float): Boolean = instanceRef.get()?.tap(x, y) ?: false
        fun performLongPress(x: Float, y: Float): Boolean = instanceRef.get()?.longPress(x, y) ?: false
        fun performScroll(x: Float, y: Float, deltaY: Float): Boolean = instanceRef.get()?.scroll(x, y, deltaY) ?: false
        fun performDrag(startX: Float, startY: Float, endX: Float, endY: Float): Boolean =
            instanceRef.get()?.drag(startX, startY, endX, endY) ?: false
    }
}
