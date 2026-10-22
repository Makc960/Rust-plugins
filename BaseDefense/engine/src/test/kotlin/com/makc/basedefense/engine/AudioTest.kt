package com.makc.basedefense.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app's audio files: a take or more of every sound effect and a loop for every track, and nothing else. */
class AudioTest {
    private val dir = File(System.getProperty("audio.dir") ?: "../app/src/main/assets/audio")

    private fun isOgg(f: File): Boolean = f.isFile && f.length() > 1000 && f.readBytes().copyOfRange(0, 4).decodeToString() == "OggS"

    @Test
    fun everySoundAndTrackHasItsFiles() {
        assertTrue(dir.isDirectory, "no audio at ${dir.absolutePath}")
        val expected = HashSet<String>()
        for (s in Sfx.entries) {
            assertTrue(isOgg(File(dir, s.file(1))), "$s has no ${s.file(1)}")
            var take = 1
            while (File(dir, s.file(take)).exists()) {
                assertTrue(isOgg(File(dir, s.file(take))), "${s.file(take)} is not Ogg")
                expected += s.file(take)
                take++
            }
        }
        for (t in Track.entries) {
            val f = File(dir, t.file)
            assertTrue(isOgg(f), "$t has no ${t.file}")
            assertTrue(f.length() in 200_000L..2_000_000L, "${t.file} is ${f.length()} bytes")
            expected += t.file
        }
        assertEquals(expected, dir.list()!!.toSet(), "every file belongs to a sound or a track")
        // The frequent sounds come in several takes.
        for (s in listOf(Sfx.SHOOT, Sfx.BOOM, Sfx.ZAP, Sfx.COIN)) assertTrue(File(dir, s.file(2)).exists(), "$s has one take")
    }
}
