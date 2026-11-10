package app.reshebnik.ui

import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.reshebnik.solver.CropArea
import java.io.File
import kotlinx.coroutines.delay

/** Рамка на экране камеры в долях экрана: то, что в неё попало, предлагается для обрезки. */
private val FRAME = CropArea(left = 0.06f, top = 0.2f, right = 0.94f, bottom = 0.62f)

/**
 * Камера во весь экран с рамкой для задания.
 *
 * @param onCaptured файл со снимком и область рамки на нём
 */
@Composable
fun CameraScreen(onCaptured: (Uri, CropArea) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    // Камера подключается не мгновенно: ждём, чтобы узнать, есть ли у неё фонарик.
    LaunchedEffect(controller) {
        repeat(30) {
            val info = controller.cameraInfo
            if (info != null) {
                hasTorch = info.hasFlashUnit()
                return@LaunchedEffect
            }
            delay(100)
        }
        error = "Камера не отвечает. Закрой этот экран и попробуй ещё раз или выбери фото из галереи."
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    // TextureView вместо SurfaceView: поверх него спокойно рисуются рамка и кнопки.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
        )
        FrameOverlay()

        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                CameraButton(AppIcons.Close, "Закрыть камеру", active = false, onClick = onClose)
                Box(Modifier.weight(1f))
                if (hasTorch) {
                    CameraButton(AppIcons.Torch, if (torch) "Выключить фонарик" else "Включить фонарик", active = torch) {
                        torch = !torch
                        controller.enableTorch(torch)
                    }
                }
            }
            Box(Modifier.weight(1f))
            Text(
                text = error ?: "Помести задание в рамку",
                color = Color.White,
                fontFamily = Sans,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp),
            )
            Shutter(enabled = !capturing && error == null) {
                capturing = true
                val file = File(File(context.cacheDir, "photos").apply { mkdirs() }, "task.jpg")
                controller.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            onCaptured(Uri.fromFile(file), FRAME)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.w("Reshebnik", "Cannot take a picture", exception)
                            capturing = false
                            error = "Не получилось сделать снимок. Попробуй ещё раз."
                        }
                    },
                )
            }
        }
    }
}

/** Затемнение вокруг рамки и её уголки. */
@Composable
private fun FrameOverlay() {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val topLeft = Offset(size.width * FRAME.left, size.height * FRAME.top)
        val frame = Size(size.width * (FRAME.right - FRAME.left), size.height * (FRAME.bottom - FRAME.top))
        val radius = CornerRadius(18.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.45f))
        drawRoundRect(Color.Transparent, topLeft, frame, radius, blendMode = BlendMode.Clear)
        drawRoundRect(Color.White.copy(alpha = 0.9f), topLeft, frame, radius, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun CameraButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = if (active) Color.Black else Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Сделать снимок" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White))
    }
}
