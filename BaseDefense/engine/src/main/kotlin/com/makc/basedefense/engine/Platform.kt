package com.makc.basedefense.engine

enum class Sfx {
    SHOOT, SHOTGUN, FLAME, ROCKET, MISSILE, ZAP, BOOM, BASE_HIT, COIN,
    BUILD, UPGRADE, SELL, WAVE, WIN, LOSE, CLICK, ERROR,
}

enum class Touch { DOWN, MOVE, UP, CANCEL }

/** What the game needs from the phone: sound, vibration and a few saved numbers. */
interface Platform {
    fun play(sfx: Sfx, volume: Float)
    fun vibrate(ms: Int)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

/** No sound, memory-only saves: the menu demo and the tests run on this. */
class SilentPlatform : Platform {
    private val values = HashMap<String, Int>()
    val played = ArrayList<Sfx>()

    override fun play(sfx: Sfx, volume: Float) {
        played += sfx
    }

    override fun vibrate(ms: Int) = Unit

    override fun getInt(key: String, default: Int): Int = values[key] ?: default

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }
}
