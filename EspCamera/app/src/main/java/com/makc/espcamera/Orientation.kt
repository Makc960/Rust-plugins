package com.makc.espcamera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Phone orientation from the rotation-vector sensor, as a rotation from
 * screen coordinates (x right, y up, z out of the screen) to world coordinates.
 */
class Orientation(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    /** Row-major 3x3, screen -> world. */
    private val matrix = FloatArray(9)
    private val raw = FloatArray(9)

    var ready = false
        private set

    var displayRotation = Surface.ROTATION_0

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
        ready = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Some phones send 5 values, which the helper rejects.
        val values = if (event.values.size > 4) event.values.copyOf(4) else event.values
        SensorManager.getRotationMatrixFromVector(raw, values)
        when (displayRotation) {
            Surface.ROTATION_90 ->
                SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, matrix)
            Surface.ROTATION_180 ->
                SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, matrix)
            Surface.ROTATION_270 ->
                SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, matrix)
            else -> raw.copyInto(matrix)
        }
        ready = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun toWorld(x: Float, y: Float, z: Float, out: FloatArray) {
        val m = matrix
        out[0] = m[0] * x + m[1] * y + m[2] * z
        out[1] = m[3] * x + m[4] * y + m[5] * z
        out[2] = m[6] * x + m[7] * y + m[8] * z
    }

    fun toScreen(world: FloatArray, out: FloatArray) {
        val m = matrix
        val x = world[0]
        val y = world[1]
        val z = world[2]
        out[0] = m[0] * x + m[3] * y + m[6] * z
        out[1] = m[1] * x + m[4] * y + m[7] * z
        out[2] = m[2] * x + m[5] * y + m[8] * z
    }
}
