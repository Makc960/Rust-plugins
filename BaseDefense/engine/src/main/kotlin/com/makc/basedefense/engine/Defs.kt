package com.makc.basedefense.engine

/**
 * Enemies. Speed is in cells per second, radius in cells; [threat] is what the
 * wave generator pays for one of them and [interval] the gap between spawns.
 */
enum class EnemyType(
    val title: String,
    val hp: Float,
    val speed: Float,
    val armor: Float,
    val reward: Int,
    val damage: Int,
    val radius: Float,
    val air: Boolean,
    val threat: Float,
    val interval: Float,
    val color: Int,
    val hint: String,
) {
    BANDIT("Бандит", 60f, 1.15f, 0f, 5, 1, 0.27f, false, 1f, 0.9f, 0xFF7A5634.toInt(), "Обычный налётчик"),
    RUNNER("Бегун", 34f, 2.1f, 0f, 4, 1, 0.23f, false, 0.9f, 0.55f, 0xFF8FC23A.toInt(), "Быстрый, но хилый"),
    SWARM("Мутант", 16f, 1.6f, 0f, 1, 1, 0.17f, false, 0.32f, 0.22f, 0xFF5FBF3F.toInt(), "Лезут толпой"),
    BRUTE("Громила", 300f, 0.7f, 4f, 15, 2, 0.4f, false, 4.5f, 1.7f, 0xFF7C8590.toInt(), "Броня 4: мелкие пули почти не берут"),
    MEDIC("Медик", 110f, 1.0f, 1f, 12, 1, 0.27f, false, 3f, 2.0f, 0xFFF2F2F2.toInt(), "Лечит всех вокруг себя"),
    DRONE("Дрон", 55f, 1.55f, 0f, 7, 1, 0.25f, true, 1.6f, 0.8f, 0xFF4A90D9.toInt(), "Летает: бьют турель, зенитка и тесла"),
    APC("Броневик", 2600f, 0.42f, 8f, 150, 8, 0.55f, false, 0f, 0f, 0xFF5D6B33.toInt(), "Босс. Из обломков вылезают бандиты"),
    HELI("Вертолёт", 2200f, 0.55f, 3f, 150, 8, 0.6f, true, 0f, 0f, 0xFF3A4652.toInt(), "Босс в воздухе: нужны зенитки"),
    ;

    val boss: Boolean get() = this == APC || this == HELI
}

/** One level of a tower; [cost] is the build price for level 1 and the upgrade price after that. */
class TowerLevel(
    val cost: Int,
    val damage: Float,
    val rate: Float,
    val range: Float,
    /** Pellets, afterburn seconds, splash radius or chain length, depending on the tower. */
    val extra: Float = 0f,
    /** Tesla slow (0..1). */
    val extra2: Float = 0f,
)

enum class TowerType(
    val title: String,
    val short: String,
    val hitsGround: Boolean,
    val hitsAir: Boolean,
    val color: Int,
    val hint: String,
    val levels: List<TowerLevel>,
) {
    TURRET(
        "Турель", "Турель", true, true, 0xFF9AA7B4.toInt(), "Быстрые пули по земле и воздуху",
        listOf(TowerLevel(50, 7f, 3.5f, 2.5f), TowerLevel(60, 11f, 4.2f, 2.7f), TowerLevel(110, 18f, 5f, 3.0f)),
    ),
    SHOTGUN(
        "Дробовик", "Дробовик", true, false, 0xFFC08A4A.toInt(), "Залп дробью в упор",
        listOf(TowerLevel(70, 7f, 1.1f, 1.9f, 6f), TowerLevel(70, 10f, 1.25f, 2.0f, 7f), TowerLevel(130, 15f, 1.4f, 2.2f, 8f)),
    ),
    FLAMER(
        "Огнемёт", "Огнемёт", true, false, 0xFFE0592B.toInt(), "Жжёт всех в конусе, броня не спасает",
        listOf(TowerLevel(90, 22f, 10f, 1.75f, 1.5f), TowerLevel(90, 34f, 10f, 1.85f, 1.8f), TowerLevel(160, 52f, 10f, 2.0f, 2.1f)),
    ),
    ROCKET(
        "Ракетница", "Ракеты", true, false, 0xFF8A9A4A.toInt(), "Медленные ракеты со взрывом",
        listOf(TowerLevel(110, 75f, 0.6f, 3.4f, 1.0f), TowerLevel(110, 115f, 0.65f, 3.6f, 1.15f), TowerLevel(190, 175f, 0.7f, 3.9f, 1.3f)),
    ),
    SAM(
        "Зенитка", "Зенитка", false, true, 0xFF6F86A8.toInt(), "Ракеты с наведением, только по воздуху",
        listOf(TowerLevel(100, 55f, 1.0f, 4.4f), TowerLevel(100, 85f, 1.1f, 4.8f), TowerLevel(170, 130f, 1.25f, 5.2f)),
    ),
    TESLA(
        "Тесла", "Тесла", true, true, 0xFF4FD8FF.toInt(), "Молния прыгает по врагам и замедляет",
        listOf(TowerLevel(110, 26f, 0.8f, 2.3f, 3f, 0.35f), TowerLevel(100, 38f, 0.9f, 2.5f, 4f, 0.4f), TowerLevel(170, 56f, 1.0f, 2.7f, 5f, 0.5f)),
    ),
}

enum class TargetMode(val title: String) {
    FIRST("ПЕРВЫЙ"),
    LAST("ПОСЛЕДНИЙ"),
    STRONG("СИЛЬНЫЙ"),
    CLOSE("БЛИЖНИЙ"),
}
