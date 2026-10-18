package com.makc.espcamera

import kotlin.math.exp
import kotlin.math.hypot

/**
 * One person's pose: 33 MediaPipe landmarks as x, y (upright image pixels)
 * and visibility (0..1), packed as [x0, y0, v0, x1, y1, v1, ...].
 */
class Skeleton(val target: FloatArray, val shown: FloatArray, var lastSeen: Long)

/** Matches poses between frames by torso position and eases their joints. */
class SkeletonTracker {
    val skeletons = mutableListOf<Skeleton>()

    fun update(poses: List<FloatArray>, now: Long, imageWidth: Int) {
        val free = skeletons.toMutableList()
        val maxJump = imageWidth * 0.25f
        for (pose in poses) {
            val cx = centerX(pose)
            val cy = centerY(pose)
            var best: Skeleton? = null
            var bestDist = maxJump
            for (s in free) {
                val d = hypot(centerX(s.target) - cx, centerY(s.target) - cy)
                if (d < bestDist) {
                    bestDist = d
                    best = s
                }
            }
            if (best != null) {
                pose.copyInto(best.target)
                best.lastSeen = now
                free.remove(best)
            } else {
                skeletons += Skeleton(pose.copyOf(), pose.copyOf(), now)
            }
        }
        skeletons.removeAll { now - it.lastSeen > LOST_MS }
    }

    fun step(dtMs: Long) {
        val k = 1f - exp(-dtMs / EASE_MS)
        for (s in skeletons) {
            var i = 0
            while (i < s.shown.size) {
                s.shown[i] += (s.target[i] - s.shown[i]) * k
                s.shown[i + 1] += (s.target[i + 1] - s.shown[i + 1]) * k
                s.shown[i + 2] = s.target[i + 2]
                i += 3
            }
        }
    }

    fun alpha(s: Skeleton, now: Long): Float {
        val age = now - s.lastSeen
        if (age <= HOLD_MS) return 1f
        return (1f - (age - HOLD_MS).toFloat() / (LOST_MS - HOLD_MS)).coerceIn(0f, 1f)
    }

    fun clear() {
        skeletons.clear()
    }

    /** Torso centre: shoulders and hips. */
    private fun centerX(p: FloatArray): Float = (p[11 * 3] + p[12 * 3] + p[23 * 3] + p[24 * 3]) / 4f

    private fun centerY(p: FloatArray): Float = (p[11 * 3 + 1] + p[12 * 3 + 1] + p[23 * 3 + 1] + p[24 * 3 + 1]) / 4f

    companion object {
        const val LANDMARKS = 33

        /** Pairs of landmark indices joined by a bone. */
        val BONES = intArrayOf(
            11, 12, // shoulders
            11, 13, 13, 15, // left arm
            12, 14, 14, 16, // right arm
            11, 23, 12, 24, 23, 24, // torso
            23, 25, 25, 27, 27, 31, // left leg
            24, 26, 26, 28, 28, 32, // right leg
        )

        /** Joints that get a dot. */
        val JOINTS = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

        const val NOSE = 0
        const val LEFT_EAR = 7
        const val RIGHT_EAR = 8
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12

        private const val HOLD_MS = 150L
        private const val LOST_MS = 500L
        private const val EASE_MS = 60f
    }
}
