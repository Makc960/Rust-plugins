package com.makc.espcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max

/**
 * Runs EfficientDet-Lite0 on camera frames in live-stream mode.
 * Results arrive on a MediaPipe thread through [onResult].
 */
class ObjectDetectorHelper(
    context: Context,
    private val onResult: (Frame) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {

    /** Detections in pixels of the upright (rotated, mirrored for selfie) frame. */
    class Frame(val detections: List<Detection>, val width: Int, val height: Int, val inferenceMs: Long)

    private val detector: ObjectDetector
    private var lastTimestamp = 0L

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .setResultListener { result: ObjectDetectorResult, image: MPImage -> deliver(result, image) }
            .setErrorListener { e: RuntimeException -> onError(e.message ?: e.toString()) }
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    /** Must be called from the camera analysis thread; closes [proxy]. */
    fun detect(proxy: ImageProxy, mirror: Boolean) {
        val timestamp = max(SystemClock.uptimeMillis(), lastTimestamp + 1)
        lastTimestamp = timestamp

        val frame = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        proxy.close()

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        detector.detectAsync(BitmapImageBuilder(upright).build(), timestamp)
    }

    private fun deliver(result: ObjectDetectorResult, image: MPImage) {
        val detections = result.detections().mapNotNull { d ->
            val category = d.categories().firstOrNull() ?: return@mapNotNull null
            val name = category.categoryName()
            if (name.isNullOrBlank() || name == "???") return@mapNotNull null
            Detection(name, category.score(), RectF(d.boundingBox()))
        }
        val inferenceMs = SystemClock.uptimeMillis() - result.timestampMs()
        onResult(Frame(detections, image.width, image.height, inferenceMs))
    }

    override fun close() {
        detector.close()
    }

    private companion object {
        const val MODEL = "efficientdet_lite0.tflite"
        const val SCORE_THRESHOLD = 0.4f
        const val MAX_RESULTS = 12
    }
}
