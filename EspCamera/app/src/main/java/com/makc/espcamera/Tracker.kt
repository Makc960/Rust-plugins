package com.makc.espcamera

import android.graphics.RectF
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One detection in upright image pixels. */
class Detection(val label: String, val score: Float, val box: RectF)

class Track(
    val id: Int,
    val label: String,
    var score: Float,
    /** Latest detected box. */
    val target: RectF,
    /** Box that is drawn; eases towards [target] every frame. */
    val shown: RectF,
    var lastSeen: Long,
)

/**
 * Keeps boxes alive between detector frames, so they glide instead of
 * jumping and every object keeps the same number while it stays in view.
 */
class Tracker {
    val tracks = mutableListOf<Track>()
    private var nextId = 1

    fun update(detections: List<Detection>, now: Long) {
        val free = tracks.toMutableList()
        for (det in detections.sortedByDescending { it.score }) {
            var best: Track? = null
            var bestIou = MATCH_IOU
            for (t in free) {
                if (t.label != det.label) continue
                val v = iou(t.target, det.box)
                if (v > bestIou) {
                    bestIou = v
                    best = t
                }
            }
            if (best != null) {
                best.target.set(det.box)
                best.score = det.score
                best.lastSeen = now
                free.remove(best)
            } else {
                tracks += Track(nextId++, det.label, det.score, RectF(det.box), RectF(det.box), now)
            }
        }
        tracks.removeAll { now - it.lastSeen > LOST_MS }
    }

    /** Moves drawn boxes towards their targets; [dtMs] is the time since the last frame. */
    fun step(dtMs: Long) {
        val k = 1f - exp(-dtMs / EASE_MS)
        for (t in tracks) {
            val s = t.shown
            val d = t.target
            s.set(
                s.left + (d.left - s.left) * k,
                s.top + (d.top - s.top) * k,
                s.right + (d.right - s.right) * k,
                s.bottom + (d.bottom - s.bottom) * k,
            )
        }
    }

    /** 1 while the object is seen, fading to 0 once it is lost. */
    fun alpha(t: Track, now: Long): Float {
        val age = now - t.lastSeen
        if (age <= HOLD_MS) return 1f
        return (1f - (age - HOLD_MS).toFloat() / (LOST_MS - HOLD_MS)).coerceIn(0f, 1f)
    }

    fun clear() {
        tracks.clear()
    }

    private fun iou(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private companion object {
        const val MATCH_IOU = 0.2f
        const val HOLD_MS = 150L
        const val LOST_MS = 600L
        const val EASE_MS = 70f
    }
}
