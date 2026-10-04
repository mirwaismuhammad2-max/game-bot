// Location in repo: app/src/main/java/com/mu/gamebot/GameBotAccessibilityService.kt
package com.mu.gamebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GameBotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GameBot"

        /** Live reference so MainActivity / overlay buttons can control the bot. */
        @Volatile
        var instance: GameBotAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }

    /** One action in a bot routine. */
    sealed class BotStep {
        data class Tap(val x: Float, val y: Float, val holdMs: Long = 50) : BotStep()
        data class LongPress(val x: Float, val y: Float, val holdMs: Long = 600) : BotStep()
        data class Swipe(
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float,
            val durationMs: Long = 300
        ) : BotStep()
        data class Wait(val ms: Long) : BotStep()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adHandler = Handler(Looper.getMainLooper())

    var isBotRunning = false
        private set

    private var steps: List<BotStep> = emptyList()
    private var stepIndex = 0
    private var loopForever = false
    private var gapBetweenStepsMs = 150L
    private var onRoutineFinished: (() -> Unit)? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for tap/swipe automation. Add logic here if you want
        // to react to screen changes (e.g. a popup appearing).
    }

    override fun onInterrupt() {
        stopBot()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopBot()
        stopAdWatcher()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopBot()
        stopAdWatcher()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- single gestures

    fun tap(x: Float, y: Float, holdMs: Long = 50, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, holdMs, onDone)
    }

    fun longPress(x: Float, y: Float, holdMs: Long = 600, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, holdMs, onDone)
    }

    fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 300, onDone: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, durationMs, onDone)
    }

    private fun dispatch(path: Path, durationMs: Long, onDone: ((Boolean) -> Unit)?) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled")
                onDone?.invoke(false)
            }
        }, null)

        if (!ok) {
            Log.e(TAG, "dispatchGesture rejected")
            onDone?.invoke(false)
        }
    }

    // ---------------------------------------------------------------- routines

    /**
     * Runs a list of steps in order. If [loop] is true it repeats until stopBot().
     */
    fun startBot(
        routine: List<BotStep>,
        loop: Boolean = true,
        gapMs: Long = 150,
        onFinished: (() -> Unit)? = null
    ) {
        if (routine.isEmpty()) return
        stopBot()
        steps = routine
        stepIndex = 0
        loopForever = loop
        gapBetweenStepsMs = gapMs
        onRoutineFinished = onFinished
        isBotRunning = true
        Log.d(TAG, "Bot started with ${routine.size} steps, loop=$loop")
        runNextStep()
    }

    fun stopBot() {
        if (!isBotRunning) return
        isBotRunning = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Bot stopped")
    }

    private fun runNextStep() {
        if (!isBotRunning) return

        if (stepIndex >= steps.size) {
            if (loopForever) {
                stepIndex = 0
            } else {
                isBotRunning = false
                onRoutineFinished?.invoke()
                return
            }
        }

        val step = steps[stepIndex++]
        val next: (Boolean) -> Unit = {
            handler.postDelayed({ runNextStep() }, gapBetweenStepsMs)
        }

        when (step) {
            is BotStep.Tap -> tap(step.x, step.y, step.holdMs, next)
            is BotStep.LongPress -> longPress(step.x, step.y, step.holdMs, next)
            is BotStep.Swipe -> swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs, next)
            is BotStep.Wait -> handler.postDelayed({ runNextStep() }, step.ms)
        }
    }

    // ---------------------------------------------------------------- screen reading

    /**
     * Grabs the current screen as a Bitmap (Android 11+ only).
     * Use it to check pixel colours before deciding where to tap.
     */
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        // Use the faster MediaProjection feed if it's running
        ScreenCaptureService.instance?.getLatestFrame()?.let {
            callback(it)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    callback(soft)
                }
                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: $errorCode")
                    callback(null)
                }
            })
    }

    /** Returns true if the pixel at (x, y) is close to [targetColor] (ARGB int). */
    fun pixelMatches(bitmap: Bitmap, x: Int, y: Int, targetColor: Int, tolerance: Int = 20): Boolean {
        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return false
        val p = bitmap.getPixel(x, y)
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        return kotlin.math.abs(ch(p, 16) - ch(targetColor, 16)) <= tolerance &&
               kotlin.math.abs(ch(p, 8) - ch(targetColor, 8)) <= tolerance &&
               kotlin.math.abs(ch(p, 0) - ch(targetColor, 0)) <= tolerance
    }

    /**
     * Finds and clicks a button by its visible text. Works on normal app UIs;
     * most games draw everything on one canvas, so use tap()/pixels there instead.
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // ---------------------------------------------------------------- ad handling

    private val adDetector by lazy { AdDetector(applicationContext) }

    var isAdWatcherOn = false
        private set

    /** Looks at the screen once and taps the close/skip button if it finds one. */
    fun closeAdIfPresent(onDone: ((Boolean) -> Unit)? = null) {
        captureScreen { bmp ->
            if (bmp == null) {
                onDone?.invoke(false)
                return@captureScreen
            }
            Thread {
                val btn = try {
                    if (adDetector.load()) adDetector.findCloseButton(bmp) else null
                } catch (e: Exception) {
                    Log.e(TAG, "Ad check failed", e)
                    null
                }
                bmp.recycle()
                handler.post {
                    if (btn != null) {
                        Log.d(TAG, "Closing ad: ${btn.label} (${btn.confidence})")
                        tap(btn.center.x, btn.center.y) { onDone?.invoke(true) }
                    } else {
                        onDone?.invoke(false)
                    }
                }
            }.start()
        }
    }

    /** Checks for ads every [intervalMs] until stopAdWatcher(). */
    fun startAdWatcher(intervalMs: Long = 3000) {
        if (isAdWatcherOn) return
        isAdWatcherOn = true
        val tick = object : Runnable {
            override fun run() {
                if (!isAdWatcherOn) return
                closeAdIfPresent { adHandler.postDelayed(this, intervalMs) }
            }
        }
        adHandler.post(tick)
    }

    fun stopAdWatcher() {
        isAdWatcherOn = false
        adHandler.removeCallbacksAndMessages(null)
    }
}
