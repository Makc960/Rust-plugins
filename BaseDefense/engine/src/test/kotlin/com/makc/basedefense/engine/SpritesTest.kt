package com.makc.basedefense.engine

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpritesTest {
    @Test
    fun atlasHasEveryPictureTheRendererDraws() {
        assertTrue(Sprites.count > 100, "no sprite index in resources")
        assertNotNull(Art.load(), "atlas.txt lacks a sprite the renderer needs")
    }

    @Test
    fun everySpriteLiesInsideTheImage() {
        val img = Sprites::class.java.getResourceAsStream(Sprites.IMAGE)!!.use { ImageIO.read(it) }
        // Phones only promise textures up to 2048 pixels.
        assertTrue(img.width <= 2048 && img.height <= 2048, "atlas is ${img.width}x${img.height}")
        val index = Sprites::class.java.getResourceAsStream(Sprites.INDEX)!!.use { it.readBytes().decodeToString() }
        val names = index.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore(' ') }
        for (name in names) {
            val s = Sprites[name]
            assertNotNull(s, "bad line for $name")
            assertTrue(s.x >= 0 && s.y >= 0 && s.x + s.w <= img.width && s.y + s.h <= img.height, "$name is outside the atlas")
            assertTrue(s.shownW > 0f && s.shownW <= s.cellsW && s.shownH <= s.cellsH, "$name shows nothing")
        }
    }
}
