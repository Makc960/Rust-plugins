package com.makc.espcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max
import kotlin.math.min

/**
 * The neural networks: EfficientDet for objects and MediaPipe Pose for
 * skeletons, both in live-stream mode, plus the picture filters.
 * Everything except the result callbacks runs on the camera analysis thread.
 */
class Vision(private val context: Context, private val listener: Listener) : AutoCloseable {

    /** Detections in pixels of the upright frame (mirrored for the selfie camera). */
    class ObjectFrame(val detections: List<Detection>, val width: Int, val height: Int, val inferenceMs: Long)

    /** Poses in the same pixels, packed as [Skeleton] expects. */
    class PoseFrame(val poses: List<FloatArray>, val width: Int, val height: Int)

    interface Listener {
        fun onObjects(frame: ObjectFrame)
        fun onPoses(frame: PoseFrame)
        fun onFiltered(bitmap: Bitmap)
        fun onConfigured(gpu: Boolean, gpuFailed: Boolean)
        fun onError(message: String)
    }

    @Volatile
    var visionMode = VisionMode.NORMAL

    /** Person boxes from the latest detection, for the thermal view. */
    @Volatile
    private var heatBoxes: List<RectF> = emptyList()

    @Volatile
    private var lastPersonAt = 0L

    private var detector: ObjectDetector? = null
    private var pose: PoseLandmarker? = null
    private var configured: String? = null
    private var lastTimestamp = 0L
    private val filter = VisualFilter()

    /** (Re)creates the networks when a setting changed. Analysis thread only. */
    fun configure(accurate: Boolean, gpu: Boolean, skeleton: Boolean) {
        val key = "$accurate/$gpu/$skeleton"
        if (key == configured) return
        configured = null
        closeNetworks()

        var useGpu = gpu
        var gpuFailed = false
        detector = try {
            createDetector(accurate, useGpu)
        } catch (e: Exception) {
            if (!useGpu) throw e
            Log.w(TAG, "GPU detector failed, falling back to CPU", e)
            useGpu = false
            gpuFailed = true
            createDetector(accurate, false)
        }
        if (skeleton) {
            pose = try {
                createPose(useGpu)
            } catch (e: Exception) {
                Log.w(TAG, "Pose on ${if (useGpu) "GPU" else "CPU"} failed", e)
                if (useGpu) runCatching { createPose(false) }.getOrNull() else null
            }
        }
        // Remember what actually runs, so the CPU fallback is not rebuilt once settings catch up.
        configured = "$accurate/$useGpu/$skeleton"
        listener.onConfigured(useGpu, gpuFailed)
    }

    /** Closes [proxy]. Analysis thread only. */
    fun analyze(proxy: ImageProxy, mirror: Boolean) {
        val objects = detector
        if (objects == null) {
            proxy.close()
            return
        }
        val timestamp = max(SystemClock.uptimeMillis(), lastTimestamp + 1)
        lastTimestamp = timestamp

        val frame = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        proxy.close()

        val upright = if (rotation == 0 && !mirror) {
            frame
        } else {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        }

        objects.detectAsync(BitmapImageBuilder(upright).build(), timestamp)

        // Skeletons only while people are around: the pose network is the heavier one.
        val poses = pose
        if (poses != null && timestamp - lastPersonAt < PERSON_WINDOW_MS) {
            poses.detectAsync(BitmapImageBuilder(upright).build(), timestamp)
        }

        val mode = visionMode
        if (mode != VisionMode.NORMAL) listener.onFiltered(filter.process(upright, mode, heatBoxes))
    }

    private fun createDetector(accurate: Boolean, gpu: Boolean): ObjectDetector {
        val model = when {
            accurate && gpu -> "efficientdet_lite2_fp16.tflite"
            accurate -> "efficientdet_lite2_int8.tflite"
            gpu -> "efficientdet_lite0_fp16.tflite"
            else -> "efficientdet_lite0_int8.tflite"
        }
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions(model, gpu))
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .setResultListener { result: ObjectDetectorResult, image: MPImage -> deliverObjects(result, image) }
            .setErrorListener { e: RuntimeException -> listener.onError(e.message ?: e.toString()) }
            .build()
        return ObjectDetector.createFromOptions(context, options)
    }

    private fun createPose(gpu: Boolean): PoseLandmarker {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions(POSE_MODEL, gpu))
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(MAX_POSES)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: PoseLandmarkerResult, image: MPImage -> deliverPoses(result, image) }
            .setErrorListener { e: RuntimeException -> listener.onError(e.message ?: e.toString()) }
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    private fun baseOptions(model: String, gpu: Boolean): BaseOptions =
        BaseOptions.builder()
            .setModelAssetPath(model)
            .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
            .build()

    private fun deliverObjects(result: ObjectDetectorResult, image: MPImage) {
        val detections = result.detections().mapNotNull { d ->
            val category = d.categories().firstOrNull() ?: return@mapNotNull null
            val name = category.categoryName()
            if (name.isNullOrBlank() || name == "???") return@mapNotNull null
            Detection(name, category.score(), RectF(d.boundingBox()))
        }
        val people = detections.filter { it.label == "person" }.map { it.box }
        heatBoxes = people
        if (people.isNotEmpty()) lastPersonAt = SystemClock.uptimeMillis()
        val inferenceMs = SystemClock.uptimeMillis() - result.timestampMs()
        listener.onObjects(ObjectFrame(detections, image.width, image.height, inferenceMs))
    }

    private fun deliverPoses(result: PoseLandmarkerResult, image: MPImage) {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val poses = result.landmarks().map { landmarks ->
            val packed = FloatArray(SkeletonTracker.LANDMARKS * 3)
            for (i in 0 until min(landmarks.size, SkeletonTracker.LANDMARKS)) {
                val lm = landmarks[i]
                packed[i * 3] = lm.x() * w
                packed[i * 3 + 1] = lm.y() * h
                packed[i * 3 + 2] = lm.visibility().orElse(1f)
            }
            packed
        }
        listener.onPoses(PoseFrame(poses, image.width, image.height))
    }

    private fun closeNetworks() {
        detector?.close()
        detector = null
        pose?.close()
        pose = null
    }

    override fun close() {
        closeNetworks()
        configured = null
    }

    private companion object {
        const val TAG = "EspVision"
        const val POSE_MODEL = "pose_landmarker_lite.task"
        const val SCORE_THRESHOLD = 0.4f
        const val MAX_RESULTS = 12
        const val MAX_POSES = 3
        const val PERSON_WINDOW_MS = 1500L
    }
}
