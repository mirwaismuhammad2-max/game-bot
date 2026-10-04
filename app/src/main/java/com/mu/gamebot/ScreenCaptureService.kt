// Location in repo: app/src/main/java/com/mu/gamebot/ScreenCaptureService.kt
package com.mu.gamebot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Continuous screen capture with MediaProjection.
 * Faster than AccessibilityService.takeScreenshot() (which is rate-limited
 * to about 1 per second) and works on Android 7–10 too.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIF_ID = 1001
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        val isCapturing: Boolean get() = instance?.virtualDisplay != null

        /** Call from MainActivity with the result of the permission dialog. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private val frameLock = Any()
    private var latestFrame: Bitmap? = null
    private var lastFrameTime = 0L

    /** Minimum gap between converted frames. Lower = smoother, more battery. */
    var minFrameIntervalMs = 100L

    var screenWidth = 0; private set
    var screenHeight = 0; private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be foreground BEFORE getMediaProjection() on Android 14+
        goForeground()

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }

        if (code != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "No capture permission, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection == null) startProjection(code, data)
        instance = this
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Game bot is watching the screen")
            .setContentText("Tap to open the app")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    @Suppress("DEPRECATION")
    private fun startProjection(code: Int, data: Intent) {
        workerThread = HandlerThread("capture").also { it.start() }
        worker = Handler(workerThread!!.looper)

        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: run {
            Log.e(TAG, "getMediaProjection returned null")
            stopSelf(); return
        }
        projection = proj

        // Required before createVirtualDisplay on Android 14+
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "Projection stopped by system/user")
                stopSelf()
            }
        }, worker)

        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameTime < minFrameIntervalMs) return@setOnImageAvailableListener
                lastFrameTime = now

                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * screenWidth
                val padded = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(plane.buffer)
                val frame = if (rowPadding == 0) padded
                    else Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight).also { padded.recycle() }

                synchronized(frameLock) {
                    latestFrame?.recycle()
                    latestFrame = frame
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame error", e)
            } finally {
                image.close()
            }
        }, worker)

        virtualDisplay = proj.createVirtualDisplay(
            "GameBotCapture",
            screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, worker
        )
        Log.d(TAG, "Capturing ${screenWidth}x$screenHeight")
    }

    /** A safe copy of the newest frame, or null if none yet. Recycle it when done. */
    fun getLatestFrame(): Bitmap? = synchronized(frameLock) {
        latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Reads one pixel without copying the whole frame (fast for colour checks). */
    fun getPixel(x: Int, y: Int): Int? = synchronized(frameLock) {
        val f = latestFrame ?: return null
        if (x !in 0 until f.width || y !in 0 until f.height) null else f.getPixel(x, y)
    }

    override fun onDestroy() {
        instance = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        workerThread?.quitSafely(); workerThread = null
        synchronized(frameLock) { latestFrame?.recycle(); latestFrame = null }
        super.onDestroy()
    }
}
