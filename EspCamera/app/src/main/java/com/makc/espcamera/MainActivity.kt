package com.makc.espcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.LiveData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private class MenuItem(val caption: Int, val value: () -> String, val onClick: () -> Unit)

    private lateinit var previewView: PreviewView
    private lateinit var overlay: EspOverlayView
    private lateinit var status: TextView
    private lateinit var controls: View
    private lateinit var bar: View
    private lateinit var menuPanel: LinearLayout
    private lateinit var btnVision: TextView
    private lateinit var btnPhoto: TextView
    private lateinit var btnRecord: TextView
    private lateinit var btnCamera: TextView
    private lateinit var btnMenu: TextView

    private lateinit var settings: Settings
    private lateinit var gyro: Orientation
    private lateinit var scene: Scene
    private lateinit var haptics: Haptics
    private lateinit var vision: Vision

    private lateinit var analysisExecutor: ExecutorService
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var mirrorFrames = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarted = false
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoEffect: OverlayEffect? = null
    private var recording: Recording? = null
    private var zoomSource: LiveData<ZoomState>? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var boundLens = -1
    private var displayRotation = -1

    private val menu = mutableListOf<Pair<TextView, MenuItem>>()
    private var pendingStorageAction: (() -> Unit)? = null

    private val videoTransform = Matrix()
    private val videoInverse = Matrix()

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionHint()
        }

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingStorageAction
            pendingStorageAction = null
            if (granted) action?.invoke() else toast(getString(R.string.no_storage))
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = updateRotation()
    }

    private val visionListener = object : Vision.Listener {
        override fun onObjects(frame: Vision.ObjectFrame) {
            overlay.post { scene.submitObjects(frame, SystemClock.uptimeMillis()) }
        }

        override fun onPoses(frame: Vision.PoseFrame) {
            overlay.post { if (settings.skeleton) scene.submitPoses(frame, SystemClock.uptimeMillis()) }
        }

        override fun onFiltered(bitmap: Bitmap) {
            overlay.post { if (settings.vision != VisionMode.NORMAL) scene.filtered = bitmap }
        }

        override fun onConfigured(gpu: Boolean, gpuFailed: Boolean) {
            overlay.post {
                scene.gpuActive = gpu
                if (gpuFailed) {
                    settings.gpu = false
                    settings.save()
                    refreshMenu()
                    toast(getString(R.string.gpu_failed))
                }
            }
        }

        override fun onError(message: String) {
            Log.w(TAG, "Vision: $message")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        controls = findViewById(R.id.controls)
        bar = findViewById(R.id.bar)
        menuPanel = findViewById(R.id.menuPanel)
        btnVision = findViewById(R.id.btnVision)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnRecord = findViewById(R.id.btnRecord)
        btnCamera = findViewById(R.id.btnCamera)
        btnMenu = findViewById(R.id.btnMenu)

        settings = Settings(this)
        gyro = Orientation(this)
        scene = Scene(settings, gyro)
        haptics = Haptics(this)
        overlay.scene = scene
        scene.onTargetLocked = { haptics.lock(settings.alert) }
        if (!gyro.available) settings.markers = false

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        hideSystemBars()
        applySafeArea()
        setupGestures()
        setupBar()
        buildMenu()

        analysisExecutor = Executors.newSingleThreadExecutor()
        vision = Vision(applicationContext, visionListener)
        configureVision()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, null)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings with the permission granted.
        if (hasCameraPermission()) startCamera()
        gyro.start()
        updateRotation()
    }

    override fun onPause() {
        super.onPause()
        gyro.stop()
    }

    override fun onStop() {
        super.onStop()
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildMenu()
        updateRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        videoEffect?.close()
        haptics.release()
        ioExecutor.shutdown()
        analysisExecutor.execute { vision.close() }
        analysisExecutor.shutdown()
    }

    // --- camera --------------------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        status.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                cameraStarted = false
                Log.e(TAG, "Camera provider failed", e)
                showStatus(getString(R.string.camera_failed, e.message ?: e.toString()))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Binds preview and analysis, plus video capture with the ESP overlay while recording. */
    private fun bindCamera(withVideo: Boolean = false): Boolean {
        val provider = cameraProvider ?: return false
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                lensFacing = CameraSelector.LENS_FACING_BACK
                toast(getString(R.string.no_front_camera))
                updateBar()
                return bindCamera(withVideo)
            }
            showStatus(getString(R.string.camera_failed, "камера не найдена"))
            return false
        }

        val rotation = currentDisplayRotation()
        displayRotation = rotation
        gyro.displayRotation = rotation

        // Same aspect ratio for every stream, so boxes line up with the preview and the video.
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

        val newPreview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .build()
        newPreview.setSurfaceProvider(previewView.surfaceProvider)

        val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        newAnalysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                vision.analyze(proxy, mirrorFrames)
            } catch (e: Exception) {
                Log.w(TAG, "Frame skipped", e)
                runCatching { proxy.close() }
            }
        }

        val group = UseCaseGroup.Builder()
            .addUseCase(newPreview)
            .addUseCase(newAnalysis)
        var newVideo: VideoCapture<Recorder>? = null
        var newEffect: OverlayEffect? = null
        if (withVideo) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setAspectRatio(AspectRatio.RATIO_16_9)
                .build()
            val video = VideoCapture.Builder<Recorder>(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .setTargetRotation(rotation)
                .build()
            val effect = createVideoEffect()
            group.addUseCase(video).addEffect(effect)
            newVideo = video
            newEffect = effect
        }

        mirrorFrames = lensFacing == CameraSelector.LENS_FACING_FRONT
        scene.front = mirrorFrames
        val sameLens = boundLens == lensFacing
        val keepZoom = if (sameLens) scene.zoom else 1f
        if (!sameLens) {
            scene.resetView()
            boundLens = lensFacing
        }

        try {
            provider.unbindAll()
            videoEffect?.close()
            videoEffect = null
            val bound = provider.bindToLifecycle(this, selector, group.build())
            camera = bound
            preview = newPreview
            analysis = newAnalysis
            videoCapture = newVideo
            videoEffect = newEffect
            readOptics(bound)
            observeZoom(bound)
            if (keepZoom != 1f) bound.cameraControl.setZoomRatio(keepZoom)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed (video: $withVideo)", e)
            newEffect?.close()
            if (withVideo) {
                toast(getString(R.string.video_unsupported))
                bindCamera(withVideo = false)
            } else {
                showStatus(getString(R.string.camera_failed, e.message ?: e.toString()))
            }
            return false
        }
    }

    /** Real focal length and sensor size, so distances match this phone's lens. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readOptics(camera: Camera) {
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
            val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            scene.focalMm = focal
            scene.sensorLongMm = sensor?.let { max(it.width, it.height) } ?: 0f
        } catch (e: Exception) {
            scene.focalMm = 0f
            scene.sensorLongMm = 0f
        }
    }

    private fun observeZoom(camera: Camera) {
        zoomSource?.removeObservers(this)
        val source = camera.cameraInfo.zoomState
        zoomSource = source
        source.observe(this) { state ->
            if (abs(state.zoomRatio - scene.zoom) > 0.01f) {
                scene.zoom = state.zoomRatio
                scene.zoomChangedAt = SystemClock.uptimeMillis()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        previewView.display?.rotation ?: windowManager.defaultDisplay.rotation

    /** Keeps frames upright when the phone turns, including the 180-degree flips. */
    private fun updateRotation() {
        val rotation = previewView.display?.rotation ?: return
        if (rotation == displayRotation) return
        displayRotation = rotation
        gyro.displayRotation = rotation
        preview?.setTargetRotation(rotation)
        analysis?.setTargetRotation(rotation)
        if (recording == null) videoCapture?.setTargetRotation(rotation)
    }

    private fun switchCamera() {
        recording?.stop()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        updateBar()
        bindCamera()
    }

    // --- photo and video -----------------------------------------------------------------

    private fun takePhoto() {
        if (MediaSaver.needsPermission(this)) {
            pendingStorageAction = { takePhoto() }
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val base = previewView.bitmap
        if (base == null) {
            toast(getString(R.string.photo_not_ready))
            return
        }
        val shot = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(shot)
        canvas.drawBitmap(base, 0f, 0f, null)
        overlay.renderStill(canvas)
        scene.flashAt = SystemClock.uptimeMillis()
        ioExecutor.execute {
            val saved = MediaSaver.savePhoto(applicationContext, shot)
            runOnUiThread { toast(getString(if (saved) R.string.photo_saved else R.string.photo_failed)) }
        }
    }

    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            btnRecord.isEnabled = false
            return
        }
        if (MediaSaver.needsPermission(this)) {
            pendingStorageAction = { toggleRecording() }
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (!bindCamera(withVideo = true)) return
        val video = videoCapture ?: return
        recording = MediaSaver.prepareVideo(this, video.output)
            .start(ContextCompat.getMainExecutor(this)) { event -> onRecordEvent(event) }
        scene.recordingSince = SystemClock.uptimeMillis()
        updateBar()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        recording = null
        scene.recordingSince = 0L
        btnRecord.isEnabled = true
        val error = event.error
        // Leaving the app stops the camera; the clip up to that point is still saved.
        if (error == VideoRecordEvent.Finalize.ERROR_NONE || error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            MediaSaver.scanLegacyVideo(this, event.outputResults.outputUri.path)
            toast(getString(R.string.video_saved))
        } else {
            toast(getString(R.string.video_failed, error))
        }
        if (!isFinishing && !isDestroyed) bindCamera(withVideo = false)
        updateBar()
    }

    private fun createVideoEffect(): OverlayEffect {
        // The main thread owns the scene, so the overlay is drawn there too.
        val effect = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(Looper.getMainLooper())) { e ->
            Log.e(TAG, "Video overlay failed", e)
        }
        effect.setOnDrawListener { frame ->
            drawVideoOverlay(frame)
            true
        }
        return effect
    }

    /** Draws the ESP layer upright into a video frame, whatever its rotation and mirroring. */
    private fun drawVideoOverlay(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val crop = frame.cropRect
        val rotation = frame.rotationDegrees
        val cw = crop.width().toFloat()
        val ch = crop.height().toFloat()
        val sideways = rotation % 180 != 0
        val w = if (sideways) ch else cw
        val h = if (sideways) cw else ch

        // Buffer -> what the viewer sees: crop, rotate clockwise, then mirror.
        videoTransform.reset()
        videoTransform.postTranslate(-crop.left.toFloat(), -crop.top.toFloat())
        videoTransform.postRotate(rotation.toFloat())
        when (rotation) {
            90 -> videoTransform.postTranslate(ch, 0f)
            180 -> videoTransform.postTranslate(cw, ch)
            270 -> videoTransform.postTranslate(0f, cw)
        }
        if (frame.isMirroring) {
            videoTransform.postScale(-1f, 1f)
            videoTransform.postTranslate(w, 0f)
        }
        if (!videoTransform.invert(videoInverse)) return
        canvas.setMatrix(videoInverse)
        overlay.renderVideo(canvas, w.toInt(), h.toInt(), frame.isMirroring)
    }

    // --- touch ---------------------------------------------------------------------------

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val state = cam.cameraInfo.zoomState.value ?: return false
                val ratio = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                return true
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (menuPanel.visibility == View.VISIBLE) {
                    setMenuOpen(false)
                } else {
                    focusAt(e.x, e.y)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                camera?.cameraControl?.setZoomRatio(1f)
                return true
            }
        })
        overlay.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        scene.focusX = x
        scene.focusY = y
        scene.focusAt = SystemClock.uptimeMillis()
    }

    // --- buttons and menu ----------------------------------------------------------------

    private fun setupBar() {
        btnVision.setOnClickListener {
            settings.vision = settings.vision.next()
            if (settings.vision == VisionMode.NORMAL) scene.filtered = null
            settings.save()
            configureVision()
            updateBar()
        }
        btnPhoto.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnCamera.setOnClickListener { switchCamera() }
        btnMenu.setOnClickListener { setMenuOpen(menuPanel.visibility != View.VISIBLE) }
        updateBar()
    }

    private fun updateBar() {
        btnVision.text = buttonText(R.string.caption_vision, settings.vision.title)
        btnPhoto.text = buttonText(R.string.caption_photo, getString(R.string.photo_action))
        val recordingNow = recording != null
        btnRecord.text = buttonText(
            R.string.caption_record,
            getString(if (recordingNow) R.string.record_stop else R.string.record_start),
            valueColor = 0xFFFF4B4B.toInt(),
        )
        btnCamera.text = buttonText(
            R.string.caption_camera,
            getString(if (lensFacing == CameraSelector.LENS_FACING_BACK) R.string.camera_back else R.string.camera_front),
        )
        btnMenu.text = buttonText(
            R.string.caption_menu,
            getString(if (menuPanel.visibility == View.VISIBLE) R.string.menu_close else R.string.menu_open),
        )
    }

    private fun setMenuOpen(open: Boolean) {
        menuPanel.visibility = if (open) View.VISIBLE else View.GONE
        updateBar()
    }

    private fun menuItems(): List<MenuItem> = listOf(
        MenuItem(R.string.caption_filter, { settings.filter.title }) {
            settings.filter = settings.filter.next()
        },
        MenuItem(R.string.caption_boxes, {
            getString(if (settings.cornerBoxes) R.string.boxes_corners else R.string.boxes_full)
        }) {
            settings.cornerBoxes = !settings.cornerBoxes
        },
        MenuItem(R.string.caption_lines, { onOff(settings.snaplines) }) {
            settings.snaplines = !settings.snaplines
        },
        MenuItem(R.string.caption_skeleton, { onOff(settings.skeleton) }) {
            settings.skeleton = !settings.skeleton
            if (!settings.skeleton) scene.skeletons.clear()
            configureVision()
        },
        MenuItem(R.string.caption_trails, { onOff(settings.trails) }) {
            settings.trails = !settings.trails
        },
        MenuItem(R.string.caption_radar, { onOff(settings.radar) }) {
            settings.radar = !settings.radar
        },
        MenuItem(R.string.caption_markers, {
            if (gyro.available) onOff(settings.markers) else getString(R.string.no_sensor)
        }) {
            if (gyro.available) {
                settings.markers = !settings.markers
                if (!settings.markers) scene.memory.clear()
            }
        },
        MenuItem(R.string.caption_alert, { settings.alert.title }) {
            settings.alert = settings.alert.next()
            haptics.lock(settings.alert)
        },
        MenuItem(R.string.caption_model, {
            getString(if (settings.accurate) R.string.model_accurate else R.string.model_fast)
        }) {
            settings.accurate = !settings.accurate
            configureVision()
        },
        MenuItem(R.string.caption_gpu, { getString(if (settings.gpu) R.string.gpu_on else R.string.gpu_off) }) {
            settings.gpu = !settings.gpu
            configureVision()
        },
    )

    /** Settings grid: two columns in portrait, five in landscape. */
    private fun buildMenu() {
        menuPanel.removeAllViews()
        menu.clear()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (landscape) 5 else 2
        val items = menuItems()
        var row: LinearLayout? = null
        for (i in items.indices) {
            if (i % columns == 0) {
                val newRow = LinearLayout(this)
                newRow.orientation = LinearLayout.HORIZONTAL
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (i > 0) params.topMargin = dp(6)
                menuPanel.addView(newRow, params)
                row = newRow
            }
            val item = items[i]
            val button = TextView(this, null, 0, R.style.HudButton)
            button.setOnClickListener {
                item.onClick()
                settings.save()
                refreshMenu()
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.marginStart = dp(3)
            params.marginEnd = dp(3)
            row?.addView(button, params)
            menu += button to item
        }
        refreshMenu()
    }

    private fun refreshMenu() {
        for ((button, item) in menu) button.text = buttonText(item.caption, item.value())
    }

    private fun onOff(value: Boolean): String = getString(if (value) R.string.on else R.string.off)

    /** Small green caption over the current value. */
    private fun buttonText(caption: Int, value: String, valueColor: Int? = null): CharSequence {
        val text = SpannableStringBuilder()
        val captionText = getString(caption)
        text.append(captionText)
        text.setSpan(RelativeSizeSpan(0.72f), 0, captionText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(0xFF39FF6A.toInt()), 0, captionText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append('\n')
        val start = text.length
        text.append(value)
        if (valueColor != null) {
            text.setSpan(ForegroundColorSpan(valueColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun configureVision() {
        val accurate = settings.accurate
        val gpu = settings.gpu
        val skeleton = settings.skeleton
        vision.visionMode = settings.vision
        analysisExecutor.execute {
            try {
                vision.configure(accurate, gpu, skeleton)
            } catch (e: Exception) {
                Log.e(TAG, "Model failed", e)
                runOnUiThread { showStatus(getString(R.string.model_failed, e.message ?: e.toString())) }
            }
        }
    }

    // --- window --------------------------------------------------------------------------

    private fun applySafeArea() {
        val root = findViewById<View>(R.id.root)
        val baseLeft = controls.paddingLeft
        val baseRight = controls.paddingRight
        val baseBottom = controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            overlay.setSafeArea(safe.left, safe.top, safe.right, safe.bottom)
            controls.updatePadding(
                left = baseLeft + safe.left,
                right = baseRight + safe.right,
                bottom = baseBottom + safe.bottom,
            )
            insets
        }
        // Snaplines start just above the button bar, whether or not the menu is open.
        controls.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            overlay.bottomReserved = bottom - top - bar.top
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showPermissionHint() {
        showStatus(getString(R.string.no_permission))
        status.setOnClickListener {
            startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "EspCamera"
    }
}
