package com.makc.espcamera

/** Where an object was last seen, as a unit direction in world coordinates. */
class Mark(val id: Int, val label: String) {
    val world = FloatArray(3)
    var distance: Float? = null
    var lastSeen = 0L

    /** When the spot came back into view without the object; 0 while it is out of view. */
    var emptySince = 0L
}

/**
 * Objects that left the frame. With the gyroscope we know which way the
 * phone turned, so an arrow can point back to where each one was.
 */
class Memory {
    val marks = LinkedHashMap<Int, Mark>()

    fun observe(id: Int, label: String, world: FloatArray, distance: Float?, now: Long) {
        var mark = marks[id]
        if (mark == null) {
            // A new number for an object we already remember: same thing seen again.
            marks.values.removeAll { it.label == label && dot(it.world, world) > SAME_SPOT_COS }
            mark = Mark(id, label)
            marks[id] = mark
        }
        world.copyInto(mark.world)
        mark.distance = distance
        mark.lastSeen = now
        mark.emptySince = 0L
        if (marks.size > MAX_MARKS) {
            marks.values.minByOrNull { it.lastSeen }?.let { marks.remove(it.id) }
        }
    }

    /**
     * Drops old marks and marks whose spot has been in view for a while
     * with nothing there. [spotVisible] says whether a mark's spot is on screen.
     */
    fun prune(now: Long, isActive: (Int) -> Boolean, spotVisible: (Mark) -> Boolean) {
        val iter = marks.values.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            if (isActive(m.id)) continue
            if (now - m.lastSeen > MAX_AGE_MS) {
                iter.remove()
                continue
            }
            if (spotVisible(m)) {
                if (m.emptySince == 0L) m.emptySince = now
                if (now - m.emptySince > EMPTY_MS) iter.remove()
            } else {
                m.emptySince = 0L
            }
        }
    }

    fun clear() {
        marks.clear()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    companion object {
        const val MAX_AGE_MS = 90_000L
        private const val EMPTY_MS = 1500L
        private const val MAX_MARKS = 24

        /** cos(12 degrees). */
        private const val SAME_SPOT_COS = 0.978f
    }
}
