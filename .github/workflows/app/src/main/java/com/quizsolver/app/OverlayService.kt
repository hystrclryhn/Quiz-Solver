package com.quizsolver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: View
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var answerText: TextView

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDpi = 420

    private var isScanning = false

    private lateinit var keyManager: KeyManager
    private lateinit var geminiClient: GeminiClient

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundWithProjectionType()

        keyManager = KeyManager(applicationContext)
        geminiClient = GeminiClient()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        releaseCapture()

        loadMetrics()
        addOverlay()

        val resultCode = intent?.getIntExtra("result_code", 0) ?: 0

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("result_data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("result_data")
        }

        if (resultCode != 0 && resultData != null) {
            try {
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(
                    projectionCallback,
                    Handler(Looper.getMainLooper())
                )

                setupCapture()
                answerText.text = "Ready"
            } catch (e: Exception) {
                answerText.text = "Err: Token"
            }
        } else {
            answerText.text = "Err: No capture"
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_service",
                "Overlay Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "overlay_service")
            .setContentTitle("Quiz Solver Overlay")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun loadMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDpi = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDpi = metrics.densityDpi
        }

        if (screenWidth <= 0) screenWidth = 1080
        if (screenHeight <= 0) screenHeight = 1920
        if (screenDpi <= 0) screenDpi = 420
    }

    private fun addOverlay() {
        if (::overlayRoot.isInitialized) {
            try {
                windowManager.removeView(overlayRoot)
            } catch (_: Exception) {
            }
        }

        overlayRoot = LayoutInflater.from(this).inflate(R.layout.overlay_answer, null)
        answerText = overlayRoot.findViewById(R.id.answerText)

        val container: LinearLayout = overlayRoot.findViewById(R.id.overlay_container)
        val scanButton: Button = overlayRoot.findViewById(R.id.scanButton)
        val closeButton: Button = overlayRoot.findViewById(R.id.closeButton)

        val prefs = getSharedPreferences("quiz_solver_prefs", Context.MODE_PRIVATE)
        val opacity = prefs.getInt("opacity", 85).coerceIn(0, 100)
        overlayRoot.alpha = opacity / 100f

        scanButton.setOnClickListener {
            startScan()
        }

        closeButton.setOnClickListener {
            stopSelf()
        }

        container.setOnTouchListener(DragListener())

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowParams.gravity = Gravity.TOP or Gravity.START
        windowParams.x = 24
        windowParams.y = 220

        windowManager.addView(overlayRoot, windowParams)
    }

    private fun startScan() {
        if (isScanning) return

        if (mediaProjection == null || imageReader == null) {
            answerText.text = "Err: No capture"
            return
        }

        isScanning = true
        answerText.text = "…"
        overlayRoot.visibility = View.INVISIBLE

        serviceScope.launch {
            delay(170)

            try {
                val bitmap = withContext(Dispatchers.IO) {
                    captureBitmap()
                }

                if (bitmap == null) {
                    showResult("Err: Capture")
                    return@launch
                }

                val base64 = withContext(Dispatchers.IO) {
                    encodeBitmap(bitmap)
                }

                val result = withContext(Dispatchers.IO) {
                    solveWithRetry(base64)
                }

                showResult(result)
            } catch (e: Exception) {
                showResult("Err: ${e.message ?: "Unknown"}")
            }
        }
    }

    private fun showResult(text: String) {
        answerText.text = text
        overlayRoot.visibility = View.VISIBLE
        isScanning = false
    }

    private suspend fun solveWithRetry(base64: String): String {
        var lastError = "Err: Net"

        repeat(2) { attempt ->
            val keys = keyManager.nextKeys(2)

            if (keys.isEmpty()) {
                return "Err: No keys"
            }

            try {
                return race(keys, base64)
            } catch (e: Exception) {
                lastError = e.message ?: "Err: Net"
            }

            if (attempt == 0) {
                delay(80)
            }
        }

        return lastError
    }

    private suspend fun race(keys: List<String>, base64: String): String = coroutineScope {
        val success = CompletableDeferred<String>()
        val failures = AtomicInteger(0)

        val jobs = keys.mapIndexed { index, key ->
            launch(Dispatchers.IO) {
                if (index > 0) {
                    delay(120)
                }

                try {
                    val raw = geminiClient.generate(key, base64)
                    val clean = AnswerParser.clean(raw)

                    if (clean != null) {
                        if (!success.isCompleted) {
                            success.complete(clean)
                        }
                    } else {
                        if (failures.incrementAndGet() >= keys.size && !success.isCompleted) {
                            success.completeExceptionally(Exception("Err: Bad"))
                        }
                    }
                } catch (e: ApiException) {
                    if (e.code == 0) {
                        keyManager.markTimeout(key)
                    } else {
                        keyManager.markHttpError(key, e.code)
                    }

                    if (failures.incrementAndGet() >= keys.size && !success.isCompleted) {
                        success.completeExceptionally(Exception("Err: API"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    keyManager.markTimeout(key)

                    if (failures.incrementAndGet() >= keys.size && !success.isCompleted) {
                        success.completeExceptionally(Exception("Err: Net"))
                    }
                }
            }
        }

        try {
            withTimeout(4200) {
                success.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw Exception("Err: Time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception(e.message ?: "Err: Net")
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    private fun setupCapture() {
        val projection = mediaProjection ?: return

        imageReader?.close()
        virtualDisplay?.release()

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "QuizSolverCapture",
            screenWidth,
            screenHeight,
            screenDpi,
            0,
            reader.surface,
            null,
            null
        )
    }

    private fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null

        try {
            if (image.width <= 0 || image.height <= 0) {
                return null
            }

            val bitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()

            if (plane.pixelStride == 4 && plane.rowStride == image.width * 4) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val colors = IntArray(image.width * image.height)

                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val idx = y * plane.rowStride + x * plane.pixelStride

                        if (idx + 2 >= buffer.limit()) {
                            continue
                        }

                        val r = buffer.get(idx).toInt() and 0xFF
                        val g = buffer.get(idx + 1).toInt() and 0xFF
                        val b = buffer.get(idx + 2).toInt() and 0xFF

                        val a = if (idx + 3 < buffer.limit()) {
                            buffer.get(idx + 3).toInt() and 0xFF
                        } else {
                            255
                        }

                        colors[y * image.width + x] =
                            (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }

                bitmap.setPixels(
                    colors,
                    0,
                    image.width,
                    0,
                    0,
                    image.width,
                    image.height
                )
            }

            val processed = cropAndScale(bitmap)

            if (processed != bitmap) {
                bitmap.recycle()
            }

            return processed
        } finally {
            image.close()
        }
    }

    private fun cropAndScale(source: Bitmap): Bitmap {
        if (source.width <= 0 || source.height <= 0) {
            return source
        }

        val top = (source.height * 0.12f).toInt().coerceAtLeast(0)
        val bottom = (source.height * 0.18f).toInt().coerceAtLeast(0)
        val availableHeight = source.height - top - bottom

        if (availableHeight <= 0) {
            return source
        }

        val cropped = Bitmap.createBitmap(
            source,
            0,
            top,
            source.width,
            availableHeight
        )

        val targetWidth = 500

        val scaled = if (cropped.width == targetWidth) {
            cropped
        } else {
            val ratio = targetWidth.toFloat() / cropped.width.toFloat()
            val targetHeight = (cropped.height * ratio).toInt().coerceAtLeast(1)

            Bitmap.createScaledBitmap(
                cropped,
                targetWidth,
                targetHeight,
                true
            )
        }

        if (cropped != source) {
            source.recycle()
        }

        if (scaled != cropped) {
            cropped.recycle()
        }

        return scaled
    }

    private fun encodeBitmap(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            58,
            outputStream
        )

        bitmap.recycle()

        return Base64.encodeToString(
            outputStream.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun releaseCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        val projection = mediaProjection

        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }

            try {
                projection.stop()
            } catch (_: Exception) {
            }
        }

        mediaProjection = null
    }

    override fun onDestroy() {
        serviceScope.cancel()

        if (::overlayRoot.isInitialized) {
            try {
                windowManager.removeView(overlayRoot)
            } catch (_: Exception) {
            }
        }

        releaseCapture()

        super.onDestroy()
    }

    private inner class DragListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (abs(dx) > 6f || abs(dy) > 6f) {
                        moved = true
                    }

                    windowParams.x = initialX + dx.toInt()
                    windowParams.y = initialY + dy.toInt()

                    try {
                        windowManager.updateViewLayout(overlayRoot, windowParams)
                    } catch (_: Exception) {
                    }

                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                    }
                    return true
                }

                else -> return false
            }
        }
    }
}
