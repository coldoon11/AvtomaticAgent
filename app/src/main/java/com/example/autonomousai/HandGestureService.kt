package com.example.autonomousai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class HandGestureService : LifecycleService() {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private lateinit var overlay: GestureOverlayController

    private var smoothX = Float.NaN
    private var smoothY = Float.NaN

    private var pinching = false
    private var pinchStartedAt = 0L
    private var pinchStartX = 0f
    private var pinchStartY = 0f
    private var dragArmed = false
    private var longPressTriggered = false
    private var lastTapAt = 0L

    private var paused = false
    private var openPalmSince = 0L
    private var openPalmConsumed = false

    private var scrollAnchorY = Float.NaN
    private var lastScrollAt = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        overlay = GestureOverlayController(this)
        createNotificationChannel()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            running = false
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Курсор: палец • pinch: тап/drag • ✋: пауза"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        overlay.show()
        setupLandmarker()
        startCamera()
    }

    private fun setupLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setResultListener(::onHandResult)
            .setErrorListener { error ->
                updateNotification("Ошибка MediaPipe: ${error.message ?: "unknown"}")
            }
            .build()
        landmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Нет разрешения на камеру")
            stopSelf()
            return
        }
        if (!overlay.canDraw()) {
            updateNotification("Нет разрешения поверх окон")
            stopSelf()
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                updateNotification("Камера недоступна")
                stopSelf()
                return@addListener
            }
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }.onFailure {
                updateNotification("Не удалось запустить фронтальную камеру")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val task = landmarker
        if (task == null) {
            imageProxy.close()
            return
        }
        try {
            val bitmap = rgbaImageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val rotated = if (rotation == 0f) bitmap else {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
            val mpImage = BitmapImageBuilder(rotated).build()
            task.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (_: Throwable) {
        } finally {
            imageProxy.close()
        }
    }

    private fun rgbaImageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width
        val paddedWidth = imageProxy.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, imageProxy.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (paddedWidth == imageProxy.width) padded else {
            Bitmap.createBitmap(padded, 0, 0, imageProxy.width, imageProxy.height).also { padded.recycle() }
        }
    }

    private fun onHandResult(
        result: HandLandmarkerResult,
        @Suppress("UNUSED_PARAMETER") input: com.google.mediapipe.framework.image.MPImage,
    ) {
        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size < 21) {
            resetPinchState()
            scrollAnchorY = Float.NaN
            if (!smoothX.isNaN() && !smoothY.isNaN()) {
                mainHandler.post {
                    overlay.updateCursor(
                        smoothX,
                        smoothY,
                        if (paused) GestureMode.PAUSED else GestureMode.MOVE,
                    )
                }
            }
            return
        }
        val now = SystemClock.uptimeMillis()

        val wrist = hand[0]
        val thumb = hand[4]
        val indexPip = hand[6]
        val index = hand[8]
        val middlePip = hand[10]
        val middle = hand[12]
        val ringPip = hand[14]
        val ring = hand[16]
        val pinkyPip = hand[18]
        val pinky = hand[20]

        val nx = (1f - index.x()).coerceIn(0f, 1f)
        val ny = index.y().coerceIn(0f, 1f)
        val targetX = nx * overlay.screenWidth()
        val targetY = ny * overlay.screenHeight()
        val alpha = 0.34f
        smoothX = if (smoothX.isNaN()) targetX else smoothX * (1f - alpha) + targetX * alpha
        smoothY = if (smoothY.isNaN()) targetY else smoothY * (1f - alpha) + targetY * alpha

        val palmA = hand[5]
        val palmB = hand[17]
        val palmWidth = hypot(palmA.x() - palmB.x(), palmA.y() - palmB.y()).coerceAtLeast(0.04f)
        val pinchRatio = hypot(index.x() - thumb.x(), index.y() - thumb.y()) / palmWidth

        val indexExtended = pointDistance(wrist.x(), wrist.y(), index.x(), index.y()) >
            pointDistance(wrist.x(), wrist.y(), indexPip.x(), indexPip.y()) * FINGER_EXTEND_RATIO
        val middleExtended = pointDistance(wrist.x(), wrist.y(), middle.x(), middle.y()) >
            pointDistance(wrist.x(), wrist.y(), middlePip.x(), middlePip.y()) * FINGER_EXTEND_RATIO
        val ringExtended = pointDistance(wrist.x(), wrist.y(), ring.x(), ring.y()) >
            pointDistance(wrist.x(), wrist.y(), ringPip.x(), ringPip.y()) * FINGER_EXTEND_RATIO
        val pinkyExtended = pointDistance(wrist.x(), wrist.y(), pinky.x(), pinky.y()) >
            pointDistance(wrist.x(), wrist.y(), pinkyPip.x(), pinkyPip.y()) * FINGER_EXTEND_RATIO

        val wasPinching = pinching
        pinching = if (pinching) pinchRatio < PINCH_RELEASE else pinchRatio < PINCH_START
        val openPalm = indexExtended && middleExtended && ringExtended && pinkyExtended && !pinching
        val twoFingerScroll = indexExtended && middleExtended && !ringExtended && !pinkyExtended && !pinching

        if (openPalm) {
            if (!openPalmConsumed) {
                if (openPalmSince == 0L) openPalmSince = now
                if (now - openPalmSince >= OPEN_PALM_HOLD_MS) {
                    paused = !paused
                    openPalmConsumed = true
                    openPalmSince = 0L
                    resetPinchState()
                    scrollAnchorY = Float.NaN
                    updateNotification(if (paused) "Жесты на паузе — ✋ ещё раз для продолжения" else "Управление жестами активно")
                }
            }
        } else {
            openPalmSince = 0L
            openPalmConsumed = false
        }

        if (paused) {
            mainHandler.post { overlay.updateCursor(smoothX, smoothY, GestureMode.PAUSED) }
            return
        }

        if (!wasPinching && pinching) {
            pinchStartedAt = now
            pinchStartX = smoothX
            pinchStartY = smoothY
            dragArmed = false
            longPressTriggered = false
            scrollAnchorY = Float.NaN
        }

        if (pinching) {
            val movement = hypot(smoothX - pinchStartX, smoothY - pinchStartY)
            val heldFor = now - pinchStartedAt
            val dragThreshold = overlay.screenWidth().coerceAtMost(overlay.screenHeight()) * DRAG_DISTANCE_RATIO

            if (!longPressTriggered && heldFor >= DRAG_ARM_MS && movement >= dragThreshold) {
                dragArmed = true
            }

            if (!dragArmed && !longPressTriggered && heldFor >= LONG_PRESS_TRIGGER_MS && movement < dragThreshold * 0.75f) {
                longPressTriggered = true
                mainHandler.post {
                    overlay.updateCursor(smoothX, smoothY, GestureMode.LONG_PRESS)
                    ScreenAccessibilityService.performLongPress(smoothX, smoothY)
                }
            } else {
                val mode = if (dragArmed) GestureMode.DRAG else GestureMode.PINCH
                mainHandler.post { overlay.updateCursor(smoothX, smoothY, mode) }
            }
            return
        }

        if (wasPinching) {
            val endX = smoothX
            val endY = smoothY
            val shouldDrag = dragArmed
            val shouldTap = !dragArmed && !longPressTriggered && now - lastTapAt >= TAP_DEBOUNCE_MS
            val startX = pinchStartX
            val startY = pinchStartY
            resetPinchState()
            mainHandler.post {
                when {
                    shouldDrag -> ScreenAccessibilityService.performDrag(startX, startY, endX, endY)
                    shouldTap -> {
                        lastTapAt = now
                        ScreenAccessibilityService.performTap(endX, endY)
                    }
                }
                overlay.updateCursor(endX, endY, GestureMode.MOVE)
            }
            return
        }

        if (twoFingerScroll) {
            val middleNy = middle.y().coerceIn(0f, 1f)
            val scrollY = ((ny + middleNy) * 0.5f) * overlay.screenHeight()
            if (scrollAnchorY.isNaN()) {
                scrollAnchorY = scrollY
            } else {
                val delta = scrollY - scrollAnchorY
                val threshold = overlay.screenHeight() * SCROLL_TRIGGER_RATIO
                if (abs(delta) >= threshold && now - lastScrollAt >= SCROLL_DEBOUNCE_MS) {
                    lastScrollAt = now
                    scrollAnchorY = scrollY
                    val scaled = delta * SCROLL_GAIN
                    mainHandler.post {
                        ScreenAccessibilityService.performScroll(smoothX, smoothY, scaled)
                    }
                }
            }
            mainHandler.post { overlay.updateCursor(smoothX, smoothY, GestureMode.SCROLL) }
            return
        }

        scrollAnchorY = Float.NaN
        mainHandler.post { overlay.updateCursor(smoothX, smoothY, GestureMode.MOVE) }
    }

    private fun resetPinchState() {
        pinching = false
        pinchStartedAt = 0L
        dragArmed = false
        longPressTriggered = false
    }

    private fun pointDistance(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(ax - bx, ay - by)

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Управление жестами", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("Autonomous AI — жесты руки")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        cameraProvider?.unbindAll()
        landmarker?.close()
        overlay.remove()
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "hand_gesture_control"
        private const val NOTIFICATION_ID = 3102

        private const val PINCH_START = 0.34f
        private const val PINCH_RELEASE = 0.52f
        private const val TAP_DEBOUNCE_MS = 360L

        private const val FINGER_EXTEND_RATIO = 1.18f
        private const val OPEN_PALM_HOLD_MS = 780L

        private const val DRAG_ARM_MS = 180L
        private const val DRAG_DISTANCE_RATIO = 0.055f
        private const val LONG_PRESS_TRIGGER_MS = 720L

        private const val SCROLL_TRIGGER_RATIO = 0.035f
        private const val SCROLL_GAIN = 2.2f
        private const val SCROLL_DEBOUNCE_MS = 170L

        @Volatile
        var running: Boolean = false
            private set
    }
}
