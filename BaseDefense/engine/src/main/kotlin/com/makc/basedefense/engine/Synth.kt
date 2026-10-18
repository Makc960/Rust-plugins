package com.makc.basedefense.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Makes every sound effect from noise and tones, so the game ships no audio files. */
object Synth {
    const val RATE = 22_050

    fun render(sfx: Sfx): ShortArray {
        val rng = Random(sfx.ordinal + 11)
        val out = when (sfx) {
            Sfx.SHOOT -> shot(0.08f, 55f, 0.45f, 1400f, rng)
            Sfx.SHOTGUN -> boom(0.28f, 13f, 0.3f, 95f, 0.5f, rng)
            Sfx.FLAME -> whoosh(0.38f, 0.06f, 0.12f, rng)
            Sfx.ROCKET -> whoosh(0.5f, 0.04f, 0.35f, rng)
            Sfx.MISSILE -> sweep(0.32f, 700f, 1700f, 0.5f, rng)
            Sfx.ZAP -> zap(0.24f, rng)
            Sfx.BOOM -> boom(0.75f, 5f, 0.06f, 55f, 0.8f, rng)
            Sfx.BASE_HIT -> boom(0.4f, 7f, 0.1f, 70f, 1f, rng)
            Sfx.COIN -> tones(floatArrayOf(1318f, 1760f), 0.06f, 0.5f)
            Sfx.BUILD -> boom(0.18f, 18f, 0.25f, 160f, 0.9f, rng)
            Sfx.UPGRADE -> tones(floatArrayOf(660f, 880f, 1100f, 1320f), 0.07f, 0.6f)
            Sfx.SELL -> tones(floatArrayOf(880f, 660f, 440f), 0.06f, 0.5f)
            Sfx.WAVE -> horn(0.9f)
            Sfx.WIN -> tones(floatArrayOf(523f, 659f, 784f, 1047f, 1047f), 0.16f, 0.6f)
            Sfx.LOSE -> tones(floatArrayOf(392f, 330f, 262f, 196f), 0.26f, 0.6f)
            Sfx.CLICK -> tones(floatArrayOf(1800f), 0.025f, 0.35f)
            Sfx.ERROR -> buzz(0.14f)
        }
        return toPcm(out)
    }

    /** 16-bit mono WAV file bytes. */
    fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = ByteArray(44 + data)
        fun int(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v shr 8).toByte()
            b[at + 2] = (v shr 16).toByte()
            b[at + 3] = (v shr 24).toByte()
        }
        fun short(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v shr 8).toByte()
        }
        "RIFF".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        int(4, 36 + data)
        "WAVEfmt ".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() }
        int(16, 16)
        short(20, 1)
        short(22, 1)
        int(24, RATE)
        int(28, RATE * 2)
        short(32, 2)
        short(34, 16)
        "data".forEachIndexed { i, c -> b[36 + i] = c.code.toByte() }
        int(40, data)
        for (i in pcm.indices) short(44 + i * 2, pcm[i].toInt())
        return b
    }

    private fun buf(seconds: Float) = FloatArray((RATE * seconds).toInt())

    private fun toPcm(f: FloatArray): ShortArray {
        var peak = 0.0001f
        for (v in f) peak = max(peak, abs(v))
        val gain = 0.9f / peak
        // Short fade at both ends so nothing clicks.
        val fade = min(f.size / 4, RATE / 400)
        return ShortArray(f.size) { i ->
            var env = 1f
            if (i < fade) env = i / fade.toFloat()
            if (i > f.size - fade) env = (f.size - i) / fade.toFloat()
            (f[i] * gain * env * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun shot(len: Float, decay: Float, tone: Float, freq: Float, rng: Random): FloatArray {
        val o = buf(len)
        var lp = 0f
        for (i in o.indices) {
            val t = i / RATE.toFloat()
            lp += (rng.nextFloat() * 2f - 1f - lp) * 0.6f
            val square = if (sin(2 * PI * freq * t * (1f - t * 4f)) > 0) 1f else -1f
            o[i] = (lp + square * tone) * exp(-t * decay)
        }
        return o
    }

    private fun boom(len: Float, decay: Float, cutoff: Float, thump: Float, thumpMix: Float, rng: Random): FloatArray {
        val o = buf(len)
        var lp = 0f
        for (i in o.indices) {
            val t = i / RATE.toFloat()
            lp += (rng.nextFloat() * 2f - 1f - lp) * cutoff
            val body = sin(2 * PI * thump * t * (1f - t)).toFloat() * exp(-t * decay * 0.8f)
            o[i] = lp * exp(-t * decay) * 2f + body * thumpMix
        }
        return o
    }

    private fun whoosh(len: Float, from: Float, to: Float, rng: Random): FloatArray {
        val o = buf(len)
        var lp = 0f
        for (i in o.indices) {
            val f = i / o.size.toFloat()
            lp += (rng.nextFloat() * 2f - 1f - lp) * (from + (to - from) * f)
            val env = min(1f, f * 8f) * (1f - f)
            o[i] = lp * env
        }
        return o
    }

    private fun sweep(len: Float, f0: Float, f1: Float, noise: Float, rng: Random): FloatArray {
        val o = buf(len)
        var phase = 0.0
        var lp = 0f
        for (i in o.indices) {
            val f = i / o.size.toFloat()
            phase += 2 * PI * (f0 + (f1 - f0) * f) / RATE
            lp += (rng.nextFloat() * 2f - 1f - lp) * 0.3f
            o[i] = (sin(phase).toFloat() * 0.6f + lp * noise) * (1f - f)
        }
        return o
    }

    private fun zap(len: Float, rng: Random): FloatArray {
        val o = buf(len)
        var phase = 0.0
        var freq = 600.0
        for (i in o.indices) {
            if (i % (RATE / 120) == 0) freq = 250.0 + rng.nextDouble() * 1100.0
            phase += 2 * PI * freq / RATE
            val f = i / o.size.toFloat()
            o[i] = (if (sin(phase) > 0) 1f else -1f) * (1f - f) * (0.6f + rng.nextFloat() * 0.4f)
        }
        return o
    }

    private fun tones(freqs: FloatArray, each: Float, decay: Float): FloatArray {
        val n = (RATE * each).toInt()
        val o = FloatArray(n * freqs.size + n * 2)
        for ((k, fr) in freqs.withIndex()) {
            for (i in 0 until n * 3) {
                val idx = k * n + i
                if (idx >= o.size) break
                val t = i / RATE.toFloat()
                o[idx] += (sin(2 * PI * fr * t).toFloat() + 0.3f * sin(4 * PI * fr * t).toFloat()) * exp(-t / (each * decay * 3f))
            }
        }
        return o
    }

    private fun horn(len: Float): FloatArray {
        val o = buf(len)
        val chord = floatArrayOf(196f, 247f, 294f)
        for (i in o.indices) {
            val t = i / RATE.toFloat()
            val f = i / o.size.toFloat()
            val vib = 1f + 0.006f * sin(2 * PI * 5.5 * t).toFloat()
            var v = 0f
            for (c in chord) {
                val p = (c * vib * t) % 1f
                v += (p * 2f - 1f) * 0.5f
            }
            o[i] = v * min(1f, f * 10f) * (1f - f) * (1f - f * 0.3f)
        }
        var lp = 0f
        for (i in o.indices) {
            lp += (o[i] - lp) * 0.25f
            o[i] = lp
        }
        return o
    }

    private fun buzz(len: Float): FloatArray {
        val o = buf(len)
        for (i in o.indices) {
            val t = i / RATE.toFloat()
            val f = i / o.size.toFloat()
            o[i] = (if (sin(2 * PI * 140 * t) > 0) 1f else -1f) * (1f - f)
        }
        return o
    }
}
