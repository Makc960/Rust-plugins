package com.makc.espcamera

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: EspOverlayView
    private lateinit var status: TextView
    private lateinit var controls: View
    private lateinit var btnFilter: TextView
    private lateinit var btnBoxes: TextView
    private lateinit var btnLines: TextView
    private lateinit var btnCamera: TextView
    private lateinit var prefs: SharedPreferences

    private lateinit var analysisExecutor: ExecutorService

    @Volatile
    private var detector: ObjectDetectorHelper? = null

    @Volatile
    private var mirrorFrames = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarted = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionHint()
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
        btnFilter = findViewById(R.id.btnFilter)
        btnBoxes = findViewById(R.id.btnBoxes)
        btnLines = findViewById(R.id.btnLines)
        btnCamera = findViewById(R.id.btnCamera)

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        hideSystemBars()
        applySafeArea()
        restoreSettings()
        bindButtons()

        analysisExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor.execute {
            try {
                detector = ObjectDetectorHelper(
                    applicationContext,
                    onResult = { frame -> overlay.post { overlay.submit(frame) } },
                    onError = { message -> Log.w(TAG, "Detector error: $message") },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Model failed", e)
                runOnUiThread { showStatus(getString(R.string.model_failed, e.message ?: e.toString())) }
            }
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings with the permission granted.
        if (hasCameraPermission()) startCamera()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute {
            detector?.close()
            detector = null
        }
        analysisExecutor.shutdown()
    }

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

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                lensFacing = CameraSelector.LENS_FACING_BACK
                Toast.makeText(this, R.string.no_front_camera, Toast.LENGTH_SHORT).show()
                updateButtons()
                bindCamera()
            } else {
                showStatus(getString(R.string.camera_failed, "камера не найдена"))
            }
            return
        }

        // Same aspect ratio for both streams, so boxes line up with the preview.
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val d = detector
            if (d == null) {
                proxy.close()
            } else {
                try {
                    d.detect(proxy, mirrorFrames)
                } catch (e: Exception) {
                    Log.w(TAG, "Frame skipped", e)
                    proxy.close()
                }
            }
        }

        mirrorFrames = lensFacing == CameraSelector.LENS_FACING_FRONT
        overlay.reset()
        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, selector, preview, analysis)
            readOptics(camera)
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            showStatus(getString(R.string.camera_failed, e.message ?: e.toString()))
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
            val sensorLong = sensor?.let { max(it.width, it.height) } ?: 0f
            overlay.setOptics(focal, sensorLong)
        } catch (e: Exception) {
            overlay.setOptics(0f, 0f)
        }
    }

    private fun bindButtons() {
        btnFilter.setOnClickListener {
            overlay.filter = overlay.filter.next()
            saveSettings()
            updateButtons()
        }
        btnBoxes.setOnClickListener {
            overlay.cornerBoxes = !overlay.cornerBoxes
            saveSettings()
            updateButtons()
        }
        btnLines.setOnClickListener {
            overlay.snaplines = !overlay.snaplines
            saveSettings()
            updateButtons()
        }
        btnCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            updateButtons()
            bindCamera()
        }
        updateButtons()
    }

    private fun updateButtons() {
        btnFilter.text = buttonText(R.string.caption_filter, overlay.filter.title)
        btnBoxes.text = buttonText(
            R.string.caption_boxes,
            getString(if (overlay.cornerBoxes) R.string.boxes_corners else R.string.boxes_full),
        )
        btnLines.text = buttonText(
            R.string.caption_lines,
            getString(if (overlay.snaplines) R.string.on else R.string.off),
        )
        btnCamera.text = buttonText(
            R.string.caption_camera,
            getString(
                if (lensFacing == CameraSelector.LENS_FACING_BACK) R.string.camera_back else R.string.camera_front,
            ),
        )
    }

    /** Small dim caption over the current value. */
    private fun buttonText(caption: Int, value: String): CharSequence {
        val text = SpannableStringBuilder()
        val captionText = getString(caption)
        text.append(captionText)
        text.setSpan(RelativeSizeSpan(0.72f), 0, captionText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(0xFF39FF6A.toInt()), 0, captionText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append('\n')
        text.append(value)
        return text
    }

    private fun restoreSettings() {
        prefs = getSharedPreferences("esp", MODE_PRIVATE)
        overlay.filter = EspOverlayView.Filter.entries
            .firstOrNull { it.name == prefs.getString("filter", null) } ?: EspOverlayView.Filter.ALL
        overlay.cornerBoxes = prefs.getBoolean("corners", true)
        overlay.snaplines = prefs.getBoolean("lines", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("filter", overlay.filter.name)
            .putBoolean("corners", overlay.cornerBoxes)
            .putBoolean("lines", overlay.snaplines)
            .apply()
    }

    private fun applySafeArea() {
        val root = findViewById<View>(R.id.root)
        val basePadding = controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            overlay.setSafeArea(safe.top, safe.bottom)
            controls.updatePadding(bottom = basePadding + safe.bottom)
            insets
        }
        controls.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            overlay.bottomReserved = bottom - top
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
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private companion object {
        const val TAG = "EspCamera"
    }
}
