package com.makc.basedefense.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Music made from code like the sound effects: each track is a seamless loop
 * of bass, pads, arpeggios, drums and a short tune.
 */
object Music {
    const val RATE = 22_050

    /** A note: when it starts and how long it lasts, in beats, and its MIDI pitch. */
    private class Note(val at: Float, val len: Float, val pitch: Int)

    private class Song(
        val bpm: Float,
        /** One chord per [barsPerChord] bars: root for the bass, then the pad tones. */
        val chords: List<IntArray>,
        val barsPerChord: Int,
        val bars: Int,
        val tune: List<Note>,
        val drums: Int,
        val arpEvery: Float,
        val bassEighths: Boolean,
    )

    private fun notes(vararg v: Number): List<Note> {
        val out = ArrayList<Note>()
        var i = 0
        while (i < v.size) {
            out += Note(v[i].toFloat(), v[i + 1].toFloat(), v[i + 2].toInt())
            i += 3
        }
        return out
    }

    private val MENU_TUNE = notes(
        0, 1, 76, 1, 0.5, 74, 1.5, 0.5, 72, 2, 2, 76,
        4, 3, 69,
        8, 1, 77, 9, 0.5, 76, 9.5, 0.5, 72, 10, 2, 69,
        12, 3, 72,
        16, 1, 79, 17, 0.5, 76, 17.5, 0.5, 74, 18, 2, 72,
        20, 3, 76,
        24, 1, 74, 25, 0.5, 71, 25.5, 0.5, 67, 26, 1, 71, 27, 1, 74,
        28, 2, 76, 30, 1, 74, 31, 1, 71,
    )

    private val BATTLE_RIFF = notes(
        0, 0.5, 69, 0.5, 0.5, 72, 1, 0.5, 76, 1.5, 0.5, 81, 2, 0.5, 79, 2.5, 0.5, 76, 3, 0.5, 72, 3.5, 0.5, 76,
        4, 1, 74, 5, 0.5, 71, 5.5, 0.5, 74, 6, 1, 79, 7, 1, 74,
        8, 0.5, 72, 8.5, 0.5, 77, 9, 1, 81, 10, 0.5, 79, 10.5, 0.5, 77, 11, 1, 72,
        12, 1, 71, 13, 0.5, 68, 13.5, 0.5, 71, 14, 1.5, 76,
    )

    private val BOSS_RIFF = notes(
        0, 1, 74, 1, 0.5, 77, 1.5, 0.5, 76, 2, 1, 74, 3, 1, 69,
        4, 1, 70, 5, 0.5, 74, 5.5, 0.5, 77, 6, 1, 82, 7, 1, 81,
        8, 1, 79, 9, 0.5, 77, 9.5, 0.5, 74, 10, 1, 70, 11, 1, 67,
        12, 0.5, 69, 12.5, 0.5, 73, 13, 0.5, 76, 13.5, 0.5, 81, 14, 1, 79, 15, 1, 76,
    )

    private fun shifted(tune: List<Note>, beats: Float): List<Note> = tune.map { Note(it.at + beats, it.len, it.pitch) }

    private fun song(track: Track): Song = when (track) {
        Track.MENU -> Song(
            84f,
            listOf(intArrayOf(45, 57, 60, 64), intArrayOf(41, 53, 57, 60), intArrayOf(48, 55, 60, 64), intArrayOf(43, 55, 59, 62)),
            barsPerChord = 2, bars = 16, tune = MENU_TUNE + shifted(MENU_TUNE, 32f), drums = 0, arpEvery = 0.5f, bassEighths = false,
        )
        Track.BATTLE -> Song(
            112f,
            listOf(intArrayOf(45, 57, 60, 64), intArrayOf(43, 55, 59, 62), intArrayOf(41, 53, 57, 60), intArrayOf(40, 52, 56, 59)),
            barsPerChord = 1, bars = 16, tune = BATTLE_RIFF + shifted(BATTLE_RIFF, 32f), drums = 1, arpEvery = 0.25f, bassEighths = true,
        )
        Track.BOSS -> Song(
            132f,
            listOf(intArrayOf(38, 50, 53, 57), intArrayOf(34, 46, 50, 53), intArrayOf(43, 55, 58, 62), intArrayOf(45, 57, 61, 64)),
            barsPerChord = 1, bars = 16, tune = shifted(BOSS_RIFF, 16f) + shifted(BOSS_RIFF, 48f), drums = 2, arpEvery = 0.25f, bassEighths = true,
        )
    }

    private fun freq(pitch: Int): Float = 440f * 2f.pow((pitch - 69) / 12f)

    fun render(track: Track): ShortArray {
        val s = song(track)
        val beat = 60f / s.bpm
        val beats = s.bars * 4
        val length = (beats * beat * RATE).toInt()
        // Notes ringing past the end wrap around to the start, so the loop has no seam.
        val buf = FloatArray(length + RATE * 2)
        val rng = Random(track.ordinal * 31 + 5)
        fun at(b: Float): Int = (b * beat * RATE).toInt()

        val chordBeats = s.barsPerChord * 4
        for (b in 0 until beats step chordBeats) {
            val chord = s.chords[(b / chordBeats) % s.chords.size]
            // Pad: the chord held with a slow swell.
            for (k in 1 until chord.size) pad(buf, at(b.toFloat()), at(chordBeats.toFloat()), freq(chord[k]), 0.045f)
            // Bass.
            val step = if (s.bassEighths) 0.5f else 1f
            var t = 0f
            while (t < chordBeats) {
                val octave = if (s.bassEighths && (t * 2).toInt() % 4 == 3) 12 else 0
                val accent = if (t % 1f == 0f) 1f else 0.75f
                bass(buf, at(b + t), at(step * 0.95f), freq(chord[0] + octave), 0.22f * accent)
                t += step
            }
            // Arpeggio over the chord tones.
            t = 0f
            var i = 0
            while (t < chordBeats) {
                val tone = chord[1 + (i % (chord.size - 1))] + (if ((i / (chord.size - 1)) % 2 == 1) 12 else 0)
                pluck(buf, at(b + t), at(0.4f), freq(tone), if (track == Track.MENU) 0.07f else 0.06f)
                t += s.arpEvery
                i++
            }
        }
        for (n in s.tune) lead(buf, at(n.at), at(n.len), freq(n.pitch), if (track == Track.MENU) 0.12f else 0.1f)
        for (b in 0 until beats) {
            when (s.drums) {
                0 -> if (b % 4 == 0) kick(buf, at(b.toFloat()), 0.35f)
                else -> {
                    if (b % 2 == 0 || (s.drums == 2 && b % 4 == 3)) kick(buf, at(b.toFloat()), 0.55f)
                    if (b % 2 == 1) snare(buf, at(b.toFloat()), 0.3f, rng)
                    for (h in 0 until (if (s.drums == 2) 4 else 2)) {
                        val off = h / (if (s.drums == 2) 4f else 2f)
                        hat(buf, at(b + off), if (h % 2 == 0) 0.07f else 0.045f, rng)
                    }
                    // A tom fill at the end of every fourth bar.
                    if (s.drums == 2 && b % 16 == 15) {
                        for (k in 0 until 4) tom(buf, at(b + k * 0.25f), 160f - k * 20f, 0.3f)
                    }
                }
            }
        }
        for (i in length until buf.size) buf[i - length] += buf[i]
        var peak = 0.0001f
        for (i in 0 until length) peak = max(peak, abs(buf[i]))
        val gain = 0.8f / peak
        return ShortArray(length) { (buf[it] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun add(buf: FloatArray, i: Int, v: Float) {
        if (i in buf.indices) buf[i] += v
    }

    private fun pad(buf: FloatArray, start: Int, len: Int, f: Float, vol: Float) {
        val release = RATE / 2
        val attack = RATE * 0.35f
        var lp = 0f
        for (voice in 0 until 2) {
            val ff = f * (if (voice == 0) 0.997f else 1.003f)
            var ph = voice * 0.37f
            for (i in 0 until len + release) {
                ph += ff / RATE
                if (ph >= 1f) ph -= 1f
                val saw = ph * 2f - 1f
                lp += (saw - lp) * 0.06f
                val env = min(1f, i / attack) * (if (i > len) 1f - (i - len) / release.toFloat() else 1f)
                add(buf, start + i, lp * env * vol)
            }
        }
    }

    private fun bass(buf: FloatArray, start: Int, len: Int, f: Float, vol: Float) {
        var ph = 0f
        var lp = 0f
        val total = len + RATE / 20
        for (i in 0 until total) {
            ph += f / RATE
            if (ph >= 1f) ph -= 1f
            val saw = ph * 2f - 1f
            val t = i / RATE.toFloat()
            lp += (saw - lp) * (0.08f + 0.25f * exp(-t * 18f))
            val env = exp(-t * 3f) * (if (i > len) 1f - (i - len) / (total - len).toFloat() else 1f)
            add(buf, start + i, (lp + sin(2 * PI * f * t).toFloat() * 0.6f) * env * vol)
        }
    }

    private fun pluck(buf: FloatArray, start: Int, len: Int, f: Float, vol: Float) {
        var ph = 0f
        for (i in 0 until len) {
            ph += f / RATE
            if (ph >= 1f) ph -= 1f
            val tri = 4f * abs(ph - 0.5f) - 1f
            val t = i / RATE.toFloat()
            add(buf, start + i, tri * exp(-t * 9f) * vol * min(1f, i / 40f))
        }
    }

    private fun lead(buf: FloatArray, start: Int, len: Int, f: Float, vol: Float) {
        var ph = 0f
        var lp = 0f
        val release = RATE / 8
        for (i in 0 until len + release) {
            val t = i / RATE.toFloat()
            val vib = 1f + 0.006f * sin(2 * PI * 5.2 * t).toFloat() * min(1f, t * 3f)
            ph += f * vib / RATE
            if (ph >= 1f) ph -= 1f
            val sq = if (ph < 0.5f) 1f else -1f
            lp += (sq - lp) * 0.18f
            val env = min(1f, i / 220f) * (0.7f + 0.3f * exp(-t * 6f)) * (if (i > len) 1f - (i - len) / release.toFloat() else 1f)
            add(buf, start + i, lp * env * vol)
        }
    }

    private fun kick(buf: FloatArray, start: Int, vol: Float) {
        var ph = 0.0
        val len = (RATE * 0.32f).toInt()
        for (i in 0 until len) {
            val t = i / RATE.toFloat()
            val f = 45.0 + 85.0 * exp(-t * 30.0)
            ph += 2 * PI * f / RATE
            add(buf, start + i, sin(ph).toFloat() * exp(-t * 9f) * vol)
        }
    }

    private fun snare(buf: FloatArray, start: Int, vol: Float, rng: Random) {
        val len = (RATE * 0.2f).toInt()
        var lp = 0f
        for (i in 0 until len) {
            val t = i / RATE.toFloat()
            val n = rng.nextFloat() * 2f - 1f
            lp += (n - lp) * 0.5f
            val tone = sin(2 * PI * 185.0 * t).toFloat() * exp(-t * 25f)
            add(buf, start + i, (lp * exp(-t * 16f) + tone * 0.5f) * vol)
        }
    }

    private fun hat(buf: FloatArray, start: Int, vol: Float, rng: Random) {
        val len = (RATE * 0.05f).toInt()
        var prev = 0f
        for (i in 0 until len) {
            val n = rng.nextFloat() * 2f - 1f
            val hp = n - prev
            prev = n
            add(buf, start + i, hp * exp(-i / RATE.toFloat() * 70f) * vol)
        }
    }

    private fun tom(buf: FloatArray, start: Int, f: Float, vol: Float) {
        var ph = 0.0
        val len = (RATE * 0.2f).toInt()
        for (i in 0 until len) {
            val t = i / RATE.toFloat()
            ph += 2 * PI * (f * (1f - t)) / RATE
            add(buf, start + i, sin(ph).toFloat() * exp(-t * 14f) * vol)
        }
    }
}
