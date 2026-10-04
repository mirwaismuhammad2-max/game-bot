// Location in repo: app/src/main/java/com/mu/gamebot/AdDetector.kt
package com.mu.gamebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfRect2d
import org.opencv.core.Rect
import org.opencv.core.Rect2d
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.FileNotFoundException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds ads and their close/skip buttons on a screenshot.
 *
 * Two detectors, use either or both:
 *  1. Template matching — put cropped PNGs of close buttons in
 *     assets/ad_templates/ (e.g. close_x.png, skip_ad.png). No training needed.
 *  2. YOLO (v5 or v8, ONNX) — put assets/ad_model.onnx and
 *     assets/ad_labels.txt (one class name per line).
 *
 * Detection is CPU-heavy: always call detect() off the main thread.
 */
class AdDetector(
    private val context: Context,
    private val cfg: Config = Config()
) {

    data class Config(
        val templateFolder: String = "ad_templates",
        val templateThreshold: Double = 0.80,
        val templateScales: List<Double> = listOf(0.6, 0.75, 0.9, 1.0, 1.15, 1.3, 1.5),
        /** Screens are shrunk to this width for template matching (speed). */
        val workWidth: Int = 720,
        val modelFile: String = "ad_model.onnx",
        val labelsFile: String = "ad_labels.txt",
        /** Must match the imgsz the model was exported with. */
        val inputSize: Int = 640,
        val confThreshold: Float = 0.45f,
        val nmsThreshold: Float = 0.45f,
        /** Labels/template names containing one of these words count as close buttons. */
        val closeKeywords: Set<String> = setOf("close", "skip", "x", "exit", "dismiss", "cross")
    )

    enum class Source { TEMPLATE, YOLO }

    data class Detection(
        val label: String,
        val confidence: Float,
        val box: RectF,          // in full-screen pixel coordinates
        val source: Source
    ) {
        val center: PointF get() = PointF(box.centerX(), box.centerY())
    }

    companion object {
        private const val TAG = "AdDetector"
    }

    private val templates = mutableListOf<Pair<String, Mat>>()
    private var net: Net? = null
    private var labels: List<String> = emptyList()

    var isReady = false
        private set
    val hasYolo: Boolean get() = net != null
    val templateCount: Int get() = templates.size

    // ---------------------------------------------------------------- setup

    /** Call once (off the main thread). Returns false if OpenCV failed to load. */
    fun load(): Boolean {
        if (isReady) return true
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV failed to load")
            return false
        }
        loadTemplates()
        loadModel()
        isReady = true
        Log.d(TAG, "Ready: ${templates.size} templates, YOLO=${net != null}")
        return true
    }

    private fun loadTemplates() {
        val names = try {
            context.assets.list(cfg.templateFolder)?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (name in names) {
            val lower = name.lowercase()
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) continue
            try {
                context.assets.open("${cfg.templateFolder}/$name").use { input ->
                    val bmp = BitmapFactory.decodeStream(input) ?: return@use
                    val rgba = Mat()
                    Utils.bitmapToMat(bmp, rgba)
                    bmp.recycle()
                    val gray = Mat()
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    rgba.release()
                    templates += name.substringBeforeLast('.') to gray
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bad template $name", e)
            }
        }
    }

    private fun loadModel() {
        try {
            val bytes = context.assets.open(cfg.modelFile).use { it.readBytes() }
            net = Dnn.readNetFromONNX(MatOfByte(*bytes)).apply {
                setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
                setPreferableTarget(Dnn.DNN_TARGET_CPU)
            }
            labels = try {
                context.assets.open(cfg.labelsFile).bufferedReader()
                    .readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
        } catch (e: FileNotFoundException) {
            Log.i(TAG, "No YOLO model in assets — template matching only")
        } catch (e: Exception) {
            Log.e(TAG, "YOLO model failed to load", e)
            net = null
        }
    }

    // ---------------------------------------------------------------- public API

    /**
     * Runs all available detectors. [region] limits the search (screen pixels),
     * e.g. the top strip where close buttons usually sit.
     */
    fun detect(bitmap: Bitmap, region: RectF? = null): List<Detection> {
        check(isReady) { "Call load() first" }
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val results = mutableListOf<Detection>()
        try {
            if (templates.isNotEmpty()) results += matchTemplates(rgba, region)
            net?.let { n ->
                val yolo = runYolo(n, rgba)
                results += if (region == null) yolo
                           else yolo.filter { region.contains(it.box.centerX(), it.box.centerY()) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
        } finally {
            rgba.release()
        }
        return results.sortedByDescending { it.confidence }
    }

    /** True if anything ad-related is on screen. */
    fun isAdShowing(bitmap: Bitmap): Boolean = detect(bitmap).isNotEmpty()

    /** Best close/skip button, or null. */
    fun findCloseButton(bitmap: Bitmap, region: RectF? = null): Detection? =
        detect(bitmap, region).firstOrNull { isCloseLabel(it.label) }

    private fun isCloseLabel(label: String): Boolean =
        label.lowercase().split('_', '-', ' ', '.').any { it in cfg.closeKeywords }

    fun release() {
        templates.forEach { it.second.release() }
        templates.clear()
        net = null
        isReady = false
    }

    // ---------------------------------------------------------------- template matching

    private fun matchTemplates(rgba: Mat, region: RectF?): List<Detection> {
        val cols = rgba.cols()
        val rows = rgba.rows()
        val roiRect = if (region == null) Rect(0, 0, cols, rows) else {
            val l = region.left.toInt().coerceIn(0, cols - 1)
            val t = region.top.toInt().coerceIn(0, rows - 1)
            val r = region.right.toInt().coerceIn(l + 1, cols)
            val b = region.bottom.toInt().coerceIn(t + 1, rows)
            Rect(l, t, r - l, b - t)
        }

        val roi = rgba.submat(roiRect)
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
        roi.release()

        // Shrink by the same factor for screen and templates so sizes stay consistent
        val factor = min(1.0, cfg.workWidth.toDouble() / cols)
        val small = Mat()
        if (factor < 1.0) Imgproc.resize(gray, small, Size(), factor, factor, Imgproc.INTER_AREA)
        else gray.copyTo(small)
        gray.release()

        val found = mutableListOf<Detection>()
        val scaled = Mat()
        val result = Mat()

        for ((name, tpl) in templates) {
            var best = -1.0
            var bestRect: Rect? = null
            for (s in cfg.templateScales) {
                val f = factor * s
                val w = (tpl.cols() * f).roundToInt()
                val h = (tpl.rows() * f).roundToInt()
                if (w < 8 || h < 8 || w > small.cols() || h > small.rows()) continue
                Imgproc.resize(
                    tpl, scaled, Size(w.toDouble(), h.toDouble()), 0.0, 0.0,
                    if (f < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
                )
                Imgproc.matchTemplate(small, scaled, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal > best) {
                    best = mm.maxVal
                    bestRect = Rect(mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt(), w, h)
                }
            }
            val r = bestRect
            if (r != null && best >= cfg.templateThreshold) {
                val box = RectF(
                    (roiRect.x + r.x / factor).toFloat(),
                    (roiRect.y + r.y / factor).toFloat(),
                    (roiRect.x + (r.x + r.width) / factor).toFloat(),
                    (roiRect.y + (r.y + r.height) / factor).toFloat()
                )
                found += Detection(name, best.toFloat(), box, Source.TEMPLATE)
            }
        }

        scaled.release(); result.release(); small.release()
        return found
    }

    // ---------------------------------------------------------------- YOLO

    private fun runYolo(net: Net, rgba: Mat): List<Detection> {
        val size = cfg.inputSize

        // Letterbox: keep aspect ratio, pad with grey
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val scale = min(size.toDouble() / rgb.cols(), size.toDouble() / rgb.rows())
        val nw = (rgb.cols() * scale).roundToInt()
        val nh = (rgb.rows() * scale).roundToInt()
        val padX = (size - nw) / 2
        val padY = (size - nh) / 2

        val resized = Mat()
        Imgproc.resize(rgb, resized, Size(nw.toDouble(), nh.toDouble()))
        val letter = Mat(size, size, CvType.CV_8UC3, Scalar(114.0, 114.0, 114.0))
        val dst = letter.submat(Rect(padX, padY, nw, nh))
        resized.copyTo(dst)
        dst.release()

        val blob = Dnn.blobFromImage(
            letter, 1.0 / 255.0, Size(size.toDouble(), size.toDouble()),
            Scalar(0.0, 0.0, 0.0), false, false
        )
        rgb.release(); resized.release(); letter.release()

        net.setInput(blob)
        val out = net.forward()
        blob.release()

        // YOLOv8: [1, 4+classes, N]   YOLOv5: [1, N, 5+classes]
        val a = out.size(1)
        val b = out.size(2)
        val isV8 = a < b
        val flat = out.reshape(1, intArrayOf(a, b))
        val table = if (isV8) flat.t() else flat
        val n = table.rows()
        val c = table.cols()
        val data = FloatArray(n * c)
        table.get(0, 0, data)
        out.release(); flat.release(); if (isV8) table.release()

        val classStart = if (isV8) 4 else 5
        val boxes = ArrayList<Rect2d>()
        val scores = ArrayList<Float>()
        val classIds = ArrayList<Int>()

        for (i in 0 until n) {
            val o = i * c
            var bestCls = -1
            var bestScore = 0f
            for (k in classStart until c) {
                val s = data[o + k]
                if (s > bestScore) { bestScore = s; bestCls = k - classStart }
            }
            if (!isV8) bestScore *= data[o + 4]   // v5 objectness
            if (bestCls < 0 || bestScore < cfg.confThreshold) continue

            val cx = data[o].toDouble()
            val cy = data[o + 1].toDouble()
            val w = data[o + 2].toDouble()
            val h = data[o + 3].toDouble()
            // Undo letterbox -> screen pixels
            val x = (cx - w / 2 - padX) / scale
            val y = (cy - h / 2 - padY) / scale
            boxes += Rect2d(x, y, w / scale, h / scale)
            scores += bestScore
            classIds += bestCls
        }
        if (boxes.isEmpty()) return emptyList()

        val keep = MatOfInt()
        Dnn.NMSBoxes(
            MatOfRect2d(*boxes.toTypedArray()),
            MatOfFloat(*scores.toFloatArray()),
            cfg.confThreshold, cfg.nmsThreshold, keep
        )

        val result = keep.toArray().map { idx ->
            val r = boxes[idx]
            Detection(
                label = labels.getOrElse(classIds[idx]) { "class_${classIds[idx]}" },
                confidence = scores[idx],
                box = RectF(
                    r.x.toFloat(), r.y.toFloat(),
                    (r.x + r.width).toFloat(), (r.y + r.height).toFloat()
                ),
                source = Source.YOLO
            )
        }
        keep.release()
        return result
    }
}
