package com.app.screensharing

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val serviceHandler by lazy { Handler(Looper.getMainLooper()) }
    private var imageThread: HandlerThread? = null
    private var imageThreadHandler: Handler? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val density = Resources.getSystem().displayMetrics.densityDpi
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentRotation = 0
    private var lastDeliveredFrameTimestampNs = 0L
    private var orientationChangeCallback: OrientationEventListener? = null
    private val imageAvailableListener = ImageReader.OnImageAvailableListener {
        try {
            val server = httpServer ?: return@OnImageAvailableListener
            imageReader?.acquireLatestImage()?.use { image ->
                val nowNs = image.timestamp.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
                if (nowNs - lastDeliveredFrameTimestampNs < 1_000_000_000L / HttpServer.Settings.maxFps) {
                    return@use
                }
                lastDeliveredFrameTimestampNs = nowNs

                val plane = image.planes[0]
                val width = plane.rowStride / plane.pixelStride
                val bitmap = if (width > image.width) {
                    val paddedBitmap = createBitmap(width, image.height)
                    try {
                        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
                        Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
                    } finally {
                        paddedBitmap.recycle()
                    }
                } else {
                    createBitmap(image.width, image.height).also {
                        it.copyPixelsFromBuffer(plane.buffer)
                    }
                }
                server.setBitmap(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private val mediaProjectionStopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            serviceHandler.post { releaseProjectionResources() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            HttpServer.Settings.sourceWidth = width
            HttpServer.Settings.sourceHeight = height

            val captureSize = scaledSize(width, height, HttpServer.Settings.maxCaptureDimension)
            if (currentWidth == captureSize.first && currentHeight == captureSize.second) {
                return
            }

            imageReader?.surface?.release()
            imageReader?.close()
            imageReader = ImageReader.newInstance(captureSize.first, captureSize.second, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, imageThreadHandler)
            virtualDisplay?.resize(captureSize.first, captureSize.second, density)
            virtualDisplay?.surface = imageReader?.surface
            currentWidth = captureSize.first
            currentHeight = captureSize.second
            lastDeliveredFrameTimestampNs = 0L
        }
    }
    private var httpServer: HttpServer? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (isStartCommand(intent)) {
            // create notification
            val notification = NotificationUtils.getNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notification.first,
                    notification.second,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(notification.first, notification.second)
            }

            ensureImageThread()
            httpServer = HttpServer(
                context = this,
                onStop = { stopService(this) },
            ).apply { start() }

            // start projection
            startProjection(
                intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED),
                intent.parcelableExtra(DATA),
            )
        } else if (isStopCommand(intent)) {
            stopProjection()
            runBlocking {
                httpServer?.destroy()
            }
            httpServer = null
            shutdownImageThread()
            stopForegroundCompat()
            stopSelf()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent?) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (mediaProjection == null && data != null) {
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(mediaProjectionStopCallback, serviceHandler)

            // register orientation change callback
            orientationChangeCallback = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation != currentRotation) {
                        currentRotation = orientation
                        try {
//                            virtualDisplay?.release()
//                            imageReader?.setOnImageAvailableListener(null, null)
//                            // re-create virtual display depending on device width / height
//                            createVirtualDisplay()

//                            virtualDisplay?.resize(currentWidth, currentWidth, density)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }.apply {
                if (canDetectOrientation()) {
                    enable()
                }
            }

            // create virtual display depending on device width / height
            createVirtualDisplay()
        }
    }

    private fun createVirtualDisplay() {
        val bounds: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val size = Point()
            wm.defaultDisplay.getRealSize(size)
            bounds = Rect(0, 0, size.x, size.y)
        }
        val sourceWidth = bounds.width()
        val sourceHeight = bounds.height()
        HttpServer.Settings.sourceWidth = sourceWidth
        HttpServer.Settings.sourceHeight = sourceHeight

        val captureSize = scaledSize(sourceWidth, sourceHeight, HttpServer.Settings.maxCaptureDimension)
        currentWidth = captureSize.first
        currentHeight = captureSize.second
        lastDeliveredFrameTimestampNs = 0L

        // start capture reader
        imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(imageAvailableListener, imageThreadHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screen sharing", currentWidth, currentHeight, density,
            virtualDisplayFlags, imageReader?.surface, null, imageThreadHandler
        )
    }

    private fun stopProjection() {
        serviceHandler.post {
            mediaProjection?.stop()
        }
    }

    private fun releaseProjectionResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.surface?.release()
        imageReader?.close()
        imageReader = null
        orientationChangeCallback?.disable()
        orientationChangeCallback = null
        mediaProjection?.unregisterCallback(mediaProjectionStopCallback)
        mediaProjection = null
        currentWidth = 0
        currentHeight = 0
        currentRotation = 0
        lastDeliveredFrameTimestampNs = 0L
        HttpServer.Settings.sourceWidth = 0
        HttpServer.Settings.sourceHeight = 0
    }

    private fun ensureImageThread() {
        if (imageThread?.isAlive == true && imageThreadHandler != null) return
        imageThread = HandlerThread("ImageThread").also { thread ->
            thread.start()
            imageThreadHandler = Handler(thread.looper)
        }
    }

    private fun shutdownImageThread() {
        imageThreadHandler = null
        imageThread?.quitSafely()
        imageThread = null
    }

    override fun onDestroy() {
        releaseProjectionResources()
        shutdownImageThread()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    object NotificationUtils {
        private const val NOTIFICATION_ID: Int = 1000
        private const val NOTIFICATION_CHANNEL_ID = "ScreenSharingChannelID"
        private const val NOTIFICATION_CHANNEL_NAME = "Screen sharing"

        fun getNotification(context: Context): Pair<Int, Notification> {
            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .setContentTitle("Screen sharing")
//                .setContentText("Screen sharing")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(true)
                .build()
            return NOTIFICATION_ID to notification
        }
    }

    companion object {
        private const val RESULT_CODE = "RESULT_CODE"
        private const val DATA = "DATA"
        private const val START = "START"
        private const val STOP = "STOP"

        fun startService(context: Context, resultCode: Int, data: Intent?) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(START)
                .putExtra(RESULT_CODE, resultCode)
                .putExtra(DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java)
                    .setAction(STOP)
            )
        }

        private fun isStartCommand(intent: Intent): Boolean {
            return intent.hasExtra(RESULT_CODE) && intent.hasExtra(DATA) && intent.action == START
        }

        private fun isStopCommand(intent: Intent): Boolean {
            return intent.action == STOP
        }

        private val virtualDisplayFlags: Int
            get() = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION //VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

        private fun scaledSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
            if (maxDimension <= 0) return width to height
            val longestSide = maxOf(width, height)
            if (longestSide <= maxDimension) return width to height

            val scale = maxDimension.toFloat() / longestSide
            return (width * scale).roundToInt().coerceAtLeast(1) to
                    (height * scale).roundToInt().coerceAtLeast(1)
        }

        private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(key: String): T? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, T::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(key)
            }
        }
    }
}
