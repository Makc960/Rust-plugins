package app.reshebnik.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.reshebnik.solver.CropArea
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Какую часть рамки тянут. */
private data class Grip(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    val moving: Boolean get() = left && top && right && bottom
}

/**
 * Снимок с рамкой обрезки: углы и стороны тянутся, за середину рамка двигается.
 * Всё за пределами рамки затемнено — нейросети уйдёт только то, что внутри.
 */
@Composable
fun CropBox(photo: Bitmap, crop: CropArea, onCropChange: (CropArea) -> Unit, modifier: Modifier = Modifier) {
    val image = remember(photo) { photo.asImageBitmap() }
    val palette = LocalPalette.current
    val currentCrop by rememberUpdatedState(crop)
    val currentOnChange by rememberUpdatedState(onCropChange)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Фото задания с рамкой обрезки" }
            .pointerInput(photo) {
                var grip: Grip? = null
                val reach = 28.dp.toPx()
                val minSide = 56.dp.toPx()
                detectDragGestures(
                    onDragStart = { position ->
                        val frame = frameRect(imageRect(Size(size.width.toFloat(), size.height.toFloat()), photo), currentCrop)
                        grip = pickGrip(position, frame, reach)
                    },
                    onDragEnd = { grip = null },
                    onDragCancel = { grip = null },
                ) { change, drag ->
                    val active = grip ?: return@detectDragGestures
                    change.consume()
                    val bounds = imageRect(Size(size.width.toFloat(), size.height.toFloat()), photo)
                    val frame = frameRect(bounds, currentCrop)
                    val moved = if (active.moving) {
                        val dx = drag.x.coerceIn(bounds.left - frame.left, bounds.right - frame.right)
                        val dy = drag.y.coerceIn(bounds.top - frame.top, bounds.bottom - frame.bottom)
                        frame.translate(dx, dy)
                    } else {
                        val limitX = min(minSide, bounds.width)
                        val limitY = min(minSide, bounds.height)
                        Rect(
                            left = if (active.left) (frame.left + drag.x).coerceIn(bounds.left, frame.right - limitX) else frame.left,
                            top = if (active.top) (frame.top + drag.y).coerceIn(bounds.top, frame.bottom - limitY) else frame.top,
                            right = if (active.right) (frame.right + drag.x).coerceIn(frame.left + limitX, bounds.right) else frame.right,
                            bottom = if (active.bottom) (frame.bottom + drag.y).coerceIn(frame.top + limitY, bounds.bottom) else frame.bottom,
                        )
                    }
                    currentOnChange(
                        CropArea(
                            left = ((moved.left - bounds.left) / bounds.width).coerceIn(0f, 1f),
                            top = ((moved.top - bounds.top) / bounds.height).coerceIn(0f, 1f),
                            right = ((moved.right - bounds.left) / bounds.width).coerceIn(0f, 1f),
                            bottom = ((moved.bottom - bounds.top) / bounds.height).coerceIn(0f, 1f),
                        ),
                    )
                }
            },
    ) {
        val bounds = imageRect(size, photo)
        drawImage(
            image = image,
            dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
            dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val frame = frameRect(bounds, crop)
        val shade = Color.Black.copy(alpha = 0.5f)
        drawRect(shade, bounds.topLeft, Size(bounds.width, frame.top - bounds.top))
        drawRect(shade, Offset(bounds.left, frame.bottom), Size(bounds.width, bounds.bottom - frame.bottom))
        drawRect(shade, Offset(bounds.left, frame.top), Size(frame.left - bounds.left, frame.height))
        drawRect(shade, Offset(frame.right, frame.top), Size(bounds.right - frame.right, frame.height))

        drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(1.5.dp.toPx()))
        // Уголки — за них удобнее всего тянуть.
        val arm = min(22.dp.toPx(), min(frame.width, frame.height) / 3f)
        val thick = 4.dp.toPx()
        for ((corner, dx, dy) in listOf(
            Triple(frame.topLeft, 1f, 1f),
            Triple(frame.topRight, -1f, 1f),
            Triple(frame.bottomLeft, 1f, -1f),
            Triple(frame.bottomRight, -1f, -1f),
        )) {
            drawLine(palette.ink, corner, corner + Offset(arm * dx, 0f), thick, StrokeCap.Round)
            drawLine(palette.ink, corner, corner + Offset(0f, arm * dy), thick, StrokeCap.Round)
        }
    }
}

/** Где на холсте лежит снимок: вписан целиком и стоит по центру. */
private fun imageRect(canvas: Size, photo: Bitmap): Rect {
    if (canvas.width <= 0f || canvas.height <= 0f) return Rect.Zero
    val scale = min(canvas.width / photo.width, canvas.height / photo.height)
    val width = photo.width * scale
    val height = photo.height * scale
    val left = (canvas.width - width) / 2f
    val top = (canvas.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun frameRect(bounds: Rect, crop: CropArea) = Rect(
    left = bounds.left + bounds.width * crop.left,
    top = bounds.top + bounds.height * crop.top,
    right = bounds.left + bounds.width * crop.right,
    bottom = bounds.top + bounds.height * crop.bottom,
)

/** @param reach на каком расстоянии от стороны палец ещё «цепляет» её */
private fun pickGrip(position: Offset, frame: Rect, reach: Float): Grip? {
    val inside = frame.inflate(reach).contains(position)
    if (!inside) return null
    val left = abs(position.x - frame.left) <= reach
    val right = !left && abs(position.x - frame.right) <= reach
    val top = abs(position.y - frame.top) <= reach
    val bottom = !top && abs(position.y - frame.bottom) <= reach
    return if (left || right || top || bottom) Grip(left, top, right, bottom) else Grip(left = true, top = true, right = true, bottom = true)
}
