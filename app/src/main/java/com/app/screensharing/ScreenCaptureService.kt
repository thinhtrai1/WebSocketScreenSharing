package com.app.screensharing

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.os.Process
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private val imageThread: HandlerThread by lazy {
        HandlerThread("ImageThread", Process.THREAD_PRIORITY_BACKGROUND)
    }
    private val imageThreadHandler: Handler by lazy { Handler(imageThread.looper) }
    private var virtualDisplay: VirtualDisplay? = null
    private val density = Resources.getSystem().displayMetrics.densityDpi
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentRotation = 0
    private var orientationChangeCallback: OrientationEventListener? = null
    private val imageAvailableListener = ImageReader.OnImageAvailableListener {
        try {
            imageReader?.acquireLatestImage()?.use { image ->
                httpServer?.apply {
                    val plane = image.planes[0]
                    val width = plane.rowStride / plane.pixelStride
                    val bitmap = if (width > image.width) {
                        Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888).let {
                            it.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(it, 0, 0, image.width, image.height)
                        }
                    } else {
                        Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
                            it.copyPixelsFromBuffer(plane.buffer)
                        }
                    }
                    setBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private val mediaProjectionStopCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler?.post {
                virtualDisplay?.release()
                imageReader?.setOnImageAvailableListener(null, null)
                orientationChangeCallback?.disable()
                mediaProjection?.unregisterCallback(this)
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (currentWidth == width && currentHeight == height) {
                return
            }

            imageReader?.surface?.release()
            imageReader?.close()
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, imageThreadHandler)
            virtualDisplay?.resize(width, height, density)
            virtualDisplay?.surface = imageReader?.surface
            currentWidth = width
            currentHeight = height
        }
    }
    private var httpServer: HttpServer? = null

    init {
        object : Thread() {
            override fun run() {
                Looper.prepare()
                handler = Handler()
                Looper.loop()
            }
        }.start()
        imageThread.start()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (isStartCommand(intent)) {
            // create notification
            val notification = NotificationUtils.getNotification(this)
            startForeground(notification.first, notification.second)

            httpServer = HttpServer(
                context = this,
                onStop = { stopService(this) },
            ).apply { start() }

            // start projection
            startProjection(
                intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED),
                intent.getParcelableExtra(DATA),
            )
        } else if (isStopCommand(intent)) {
            stopProjection()
            runBlocking(Dispatchers.Unconfined) {
                httpServer?.destroy()
            }
            imageThread.quit()
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
            // create virtual display depending on device width / height
            createVirtualDisplay()

            // register orientation change callback
            orientationChangeCallback = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation != currentRotation) {
                        currentRotation = orientation
                        try {
                            virtualDisplay?.release()
                            imageReader?.setOnImageAvailableListener(null, null)
                            // re-create virtual display depending on device width / height
                            createVirtualDisplay()
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

            // register media projection stop callback
            mediaProjection?.registerCallback(mediaProjectionStopCallback, handler)
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
        currentWidth = bounds.width()
        currentHeight = bounds.height()

        // start capture reader
        imageReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screen sharing", currentWidth, currentHeight, density,
            virtualDisplayFlags, imageReader?.surface, null, imageThreadHandler
        )
        imageReader?.setOnImageAvailableListener(imageAvailableListener, imageThreadHandler)
    }

    private fun stopProjection() {
        handler?.post {
            mediaProjection?.stop()
        }
    }

    object NotificationUtils {
        private const val NOTIFICATION_ID: Int = 1000
        private const val NOTIFICATION_CHANNEL_ID = "ScreenSharingChannelID"
        private const val NOTIFICATION_CHANNEL_NAME = "Screen sharing"

        fun getNotification(context: Context): Pair<Int, Notification> {
            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                notificationManager.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .setContentTitle("Screen sharing")
//                .setContentText("Screen sharing")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setShowWhen(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
            return NOTIFICATION_ID to notification
        }
    }

    companion object {
        private const val RESULT_CODE = "RESULT_CODE"
        private const val DATA = "DATA"
        private const val START = "START"
        private const val STOP = "STOP"

        fun startService(context: Context, resultCode: Int, data: Intent?) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java)
                    .setAction(START)
                    .putExtra(RESULT_CODE, resultCode)
                    .putExtra(DATA, data)
            )
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
    }
}