package com.makc.basedefense.engine

/**
 * Enemies. Speed is in cells per second, radius in cells; [threat] is what the
 * wave generator pays for one of them and [interval] the gap between spawns.
 * [campaign] is the first campaign level that sends them.
 */
enum class EnemyType(
    val title: L,
    val hint: L,
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
    val campaign: Int,
    /** Recharging shield, as a share of max health. */
    val shield: Float = 0f,
    /** Towers only see it next to a radar. */
    val stealth: Boolean = false,
) {
    BANDIT(
        L("Бандит", "Bandit"), L("Обычный налётчик", "A plain raider"),
        60f, 1.15f, 0f, 5, 1, 0.27f, false, 1f, 0.9f, 0xFF7A5634.toInt(), 1,
    ),
    RUNNER(
        L("Бегун", "Runner"), L("Быстрый, но хилый", "Fast but frail"),
        34f, 2.1f, 0f, 4, 1, 0.23f, false, 0.9f, 0.55f, 0xFF8FC23A.toInt(), 1,
    ),
    SWARM(
        L("Мутант", "Mutant"), L("Лезут толпой", "Comes in crowds"),
        16f, 1.6f, 0f, 1, 1, 0.17f, false, 0.32f, 0.22f, 0xFF5FBF3F.toInt(), 2,
    ),
    BRUTE(
        L("Громила", "Brute"), L("Броня 4: мелкие пули почти не берут", "Armor 4: small bullets barely scratch it"),
        300f, 0.7f, 4f, 15, 2, 0.4f, false, 4.5f, 1.7f, 0xFF7C8590.toInt(), 3,
    ),
    MEDIC(
        L("Медик", "Medic"), L("Лечит всех вокруг себя", "Heals everyone around"),
        110f, 1.0f, 1f, 12, 1, 0.27f, false, 3f, 2.0f, 0xFFF2F2F2.toInt(), 4,
    ),
    DRONE(
        L("Дрон", "Drone"), L("Летает: бьют стрелки, ПЗРК, тесла и снайперы", "Flies: hit by riflemen, Stingers, Tesla and snipers"),
        55f, 1.55f, 0f, 7, 1, 0.25f, true, 1.6f, 0.8f, 0xFF4A90D9.toInt(), 4,
    ),
    SHIELD(
        L("Щитоносец", "Shieldbearer"), L("Щит восстанавливается, если 3 секунды его не бить", "Its shield recharges after 3 s without hits"),
        150f, 0.9f, 1f, 14, 2, 0.32f, false, 4.2f, 1.6f, 0xFF5B8FB9.toInt(), 10, shield = 0.8f,
    ),
    GHOST(
        L("Невидимка", "Ghost"), L("Отряды видят его только рядом с радаром. Взрывы и огонь задевают", "Squads see it only near a radar. Blasts and fire still hit it"),
        70f, 1.45f, 0f, 9, 1, 0.26f, false, 2f, 1.0f, 0xFF9A6BD6.toInt(), 12, stealth = true,
    ),
    SAPPER(
        L("Сапёр", "Sapper"), L("Глушит отряды, мимо которых проходит", "Jams the squads it walks past"),
        95f, 1.3f, 0f, 10, 1, 0.27f, false, 2.4f, 1.4f, 0xFFE0B020.toInt(), 13,
    ),
    MOTHER(
        L("Матка", "Brood Mother"), L("Погибая, выпускает рой мутантов", "Releases a swarm when it dies"),
        420f, 0.6f, 2f, 10, 3, 0.42f, false, 6.5f, 2.6f, 0xFF8A7A3A.toInt(), 14,
    ),
    APC(
        L("Броневик", "APC"), L("Босс. Из обломков вылезают бандиты", "Boss. Bandits climb out of the wreck"),
        2600f, 0.42f, 8f, 150, 8, 0.55f, false, 0f, 0f, 0xFF5D6B33.toInt(), 6,
    ),
    HELI(
        L("Вертолёт", "Helicopter"), L("Босс в воздухе: нужны ПЗРК", "Flying boss: bring Stingers"),
        2200f, 0.55f, 3f, 150, 8, 0.6f, true, 0f, 0f, 0xFF3A4652.toInt(), 7,
    ),
    ;

    val boss: Boolean get() = this == APC || this == HELI
    val key: String get() = "e:$name"
}

/**
 * One level of a tower. [cost] is the build price for level 1 and the upgrade
 * price after that; [extra] and [extra2] mean different things per tower.
 */
class TowerLevel(
    val cost: Int,
    val damage: Float,
    val rate: Float,
    val range: Float,
    val extra: Float = 0f,
    val extra2: Float = 0f,
)

enum class Role { ATTACK, ROAD, SUPPORT, ECONOMY }

/** [unlock] is the campaign level where the tower first becomes available. */
enum class TowerType(
    val title: L,
    val short: L,
    val hint: L,
    val role: Role,
    val hitsGround: Boolean,
    val hitsAir: Boolean,
    val color: Int,
    val unlock: Int,
    val levels: List<TowerLevel>,
) {
    TURRET(
        L("Стрелки", "Riflemen"), L("Стрелки", "Riflemen"), L("Автоматы: быстрые пули по земле и воздуху", "Rifles: fast bullets at ground and air"),
        Role.ATTACK, true, true, 0xFF9AA7B4.toInt(), 1,
        listOf(TowerLevel(50, 7f, 3.5f, 2.5f), TowerLevel(60, 11f, 4.2f, 2.7f), TowerLevel(110, 18f, 5f, 3.0f)),
    ),
    SHOTGUN(
        L("Штурмовики", "Shotgunners"), L("Дробовик", "Shotgun"), L("Залп дробью в упор", "A spread of pellets at close range"),
        Role.ATTACK, true, false, 0xFFC08A4A.toInt(), 1,
        // extra = pellets
        listOf(TowerLevel(70, 7f, 1.1f, 1.9f, 6f), TowerLevel(70, 10f, 1.25f, 2.0f, 7f), TowerLevel(130, 15f, 1.4f, 2.2f, 8f)),
    ),
    ROCKET(
        L("Гранатомётчики", "Bazooka team"), L("Базука", "Bazooka"), L("Медленные ракеты со взрывом", "Slow rockets that blow up an area"),
        Role.ATTACK, true, false, 0xFF8A9A4A.toInt(), 1,
        // extra = blast radius
        listOf(TowerLevel(110, 75f, 0.6f, 3.4f, 1.0f), TowerLevel(110, 115f, 0.65f, 3.6f, 1.15f), TowerLevel(190, 175f, 0.7f, 3.9f, 1.3f)),
    ),
    FLAMER(
        L("Огнемётчики", "Flamethrowers"), L("Огнемёт", "Flamer"), L("Жжёт всех в конусе, броня не спасает", "Burns everyone in a cone, armor does not help"),
        Role.ATTACK, true, false, 0xFFE0592B.toInt(), 2,
        // damage = per second, extra = afterburn seconds
        listOf(TowerLevel(90, 22f, 10f, 1.75f, 1.5f), TowerLevel(90, 34f, 10f, 1.85f, 1.8f), TowerLevel(160, 52f, 10f, 2.0f, 2.1f)),
    ),
    SAM(
        L("Расчёт ПЗРК", "Stinger team"), L("ПЗРК", "Stinger"), L("Ракеты с плеча с наведением, только по воздуху", "Shoulder-fired homing missiles, air only"),
        Role.ATTACK, false, true, 0xFF6F86A8.toInt(), 4,
        listOf(TowerLevel(100, 55f, 1.0f, 4.4f), TowerLevel(100, 85f, 1.1f, 4.8f), TowerLevel(170, 130f, 1.25f, 5.2f)),
    ),
    TESLA(
        L("Тесла-пехота", "Tesla troopers"), L("Тесла", "Tesla"), L("Молния прыгает по врагам и замедляет", "Lightning chains between enemies and slows them"),
        Role.ATTACK, true, true, 0xFF4FD8FF.toInt(), 5,
        // extra = chain length, extra2 = slow
        listOf(TowerLevel(110, 26f, 0.8f, 2.3f, 3f, 0.35f), TowerLevel(100, 38f, 0.9f, 2.5f, 4f, 0.4f), TowerLevel(170, 56f, 1.0f, 2.7f, 5f, 0.5f)),
    ),
    SNIPER(
        L("Снайперы", "Snipers"), L("Снайпер", "Sniper"), L("Очень дальний мощный выстрел, броня вдвое слабее", "Long heavy shots; armor counts half"),
        Role.ATTACK, true, true, 0xFF7E8C6A.toInt(), 9,
        listOf(TowerLevel(120, 90f, 0.45f, 5.5f), TowerLevel(110, 145f, 0.5f, 6.0f), TowerLevel(180, 230f, 0.55f, 6.5f)),
    ),
    MINES(
        L("Минёры", "Minelayers"), L("Мины", "Mines"), L("Минёр ставит мину на дорогу, она взрывается под врагами", "A minelayer puts a mine on the road that blows up under enemies"),
        Role.ROAD, true, false, 0xFFB0463A.toInt(), 11,
        // rate = re-arms per second, range = blast radius
        listOf(TowerLevel(60, 90f, 1 / 3f, 0.9f), TowerLevel(60, 140f, 1 / 2.6f, 1.0f), TowerLevel(110, 210f, 1 / 2.2f, 1.1f)),
    ),
    RADAR(
        L("Радар", "Radar post"), L("Радар", "Radar"), L("Показывает невидимок и усиливает отряды рядом", "Reveals ghosts and boosts nearby squads"),
        Role.SUPPORT, false, false, 0xFF5FA8A0.toInt(), 12,
        // extra = damage aura
        listOf(TowerLevel(90, 0f, 0f, 2.6f, 0.08f), TowerLevel(80, 0f, 0f, 3.0f, 0.12f), TowerLevel(130, 0f, 0f, 3.4f, 0.16f)),
    ),
    QUARRY(
        L("Снабжение", "Supply"), L("Склад", "Supply"), L("Приносит монеты в начале каждой волны", "Earns coins at the start of every wave"),
        Role.ECONOMY, false, false, 0xFFB08A50.toInt(), 17,
        // damage = coins per wave
        listOf(TowerLevel(100, 15f, 0f, 0f), TowerLevel(90, 28f, 0f, 0f), TowerLevel(140, 45f, 0f, 0f)),
    ),
    ;

    val key: String get() = "t:$name"
    val branches: List<Branch> get() = Branch.entries.filter { it.tower == this }

    /** Towers that shoot and so can pick a target. */
    val aims: Boolean get() = role == Role.ATTACK
}

/** The fourth level: every tower splits into two specialisations. */
enum class Branch(val tower: TowerType, val title: L, val hint: L, val stats: TowerLevel) {
    MINIGUN(
        TowerType.TURRET, L("Пулемёт", "Minigun"), L("Раскручивается до 14 выстрелов в секунду", "Spins up to 14 shots a second"),
        TowerLevel(240, 13f, 14f, 3.1f),
    ),
    PIERCER(
        TowerType.TURRET, L("Бронебой", "Piercer"), L("Тяжёлые пули прошивают любую броню", "Heavy rounds ignore all armor"),
        TowerLevel(240, 60f, 2.5f, 3.6f),
    ),
    ASSAULT(
        TowerType.SHOTGUN, L("Штурм", "Assault"), L("10 дробин, отбрасывают врагов назад", "10 pellets that knock enemies back"),
        TowerLevel(260, 20f, 1.7f, 2.4f, 10f, 0.35f),
    ),
    SLUG(
        TowerType.SHOTGUN, L("Жакан", "Slug"), L("Пуля пробивает до 4 врагов в линию", "A slug that pierces up to 4 enemies"),
        TowerLevel(260, 220f, 1.0f, 3.0f, 4f),
    ),
    CLUSTER(
        TowerType.ROCKET, L("Кассета", "Cluster"), L("Ракета рассыпается на 6 бомбочек", "Each rocket splits into 6 bomblets"),
        TowerLevel(300, 140f, 0.75f, 4.1f, 1.0f, 55f),
    ),
    HEAVY(
        TowerType.ROCKET, L("Тяжёлая ракета", "Heavy rocket"), L("Огромный взрыв, ×1,5 по боссам", "A huge blast, ×1.5 against bosses"),
        TowerLevel(300, 600f, 0.42f, 4.6f, 1.5f),
    ),
    NAPALM(
        TowerType.FLAMER, L("Напалм", "Napalm"), L("Оставляет на дороге горящие лужи", "Leaves burning pools on the road"),
        TowerLevel(280, 75f, 10f, 2.2f, 2.5f),
    ),
    CRYO(
        TowerType.FLAMER, L("Криоган", "Cryo"), L("Сильно замедляет; замёрзшие получают +25% урона", "Slows hard; frozen enemies take 25% more damage"),
        TowerLevel(260, 30f, 10f, 2.2f, 0f, 0.65f),
    ),
    SALVO(
        TowerType.SAM, L("Залп", "Salvo"), L("Три ракеты сразу по разным целям", "Three missiles at three targets"),
        TowerLevel(280, 160f, 1.25f, 5.5f, 3f),
    ),
    LONGBOW(
        TowerType.SAM, L("ПВО", "Longbow"), L("Очень далеко и ×2 по боссам", "Very long range, ×2 against bosses"),
        TowerLevel(280, 480f, 0.85f, 7.5f),
    ),
    STORM(
        TowerType.TESLA, L("Буря", "Storm"), L("Молния прыгает по 9 врагам", "Lightning jumps across 9 enemies"),
        TowerLevel(300, 80f, 1.15f, 3.0f, 9f, 0.5f),
    ),
    STUN(
        TowerType.TESLA, L("Перегрузка", "Overload"), L("Оглушает врагов на 0,7 секунды", "Stuns enemies for 0.7 seconds"),
        TowerLevel(280, 95f, 1.0f, 2.9f, 5f, 0.7f),
    ),
    HEAVY_CAL(
        TowerType.SNIPER, L("Крупный калибр", "Heavy caliber"), L("Огромный урон сквозь любую броню", "Huge damage through any armor"),
        TowerLevel(320, 700f, 0.5f, 7.5f),
    ),
    MARKER(
        TowerType.SNIPER, L("Метка", "Marker"), L("Помеченная цель получает +30% урона от всех", "Marked targets take 30% more damage"),
        TowerLevel(300, 320f, 0.75f, 7.0f, 4f),
    ),
    LANDMINE(
        TowerType.MINES, L("Фугас", "Landmine"), L("Огромный взрыв раз в 4 секунды", "A huge blast every 4 seconds"),
        TowerLevel(240, 650f, 0.25f, 1.5f),
    ),
    SPIKES(
        TowerType.MINES, L("Колючка", "Barbed wire"), L("Колючая проволока ранит и замедляет всех на клетке", "Barbed wire hurts and slows everyone on the cell"),
        TowerLevel(220, 70f, 0f, 0.6f, 0.3f),
    ),
    COMMAND(
        TowerType.RADAR, L("Штаб", "Command post"), L("+25% урона и +10% дальности отрядам рядом", "+25% damage and +10% range to nearby squads"),
        TowerLevel(220, 0f, 0f, 3.8f, 0.25f, 0.1f),
    ),
    JAMMER(
        TowerType.RADAR, L("Глушилка", "Jammer"), L("Видит дальше, замедляет врагов, защищает от сапёров", "Sees farther, slows enemies, stops sappers"),
        TowerLevel(220, 0f, 0f, 4.6f, 0.08f, 0.2f),
    ),
    DRILL(
        TowerType.QUARRY, L("Шахта", "Mine shaft"), L("+80 монет каждую волну", "+80 coins every wave"),
        TowerLevel(220, 80f, 0f, 0f),
    ),
    BANK(
        TowerType.QUARRY, L("Банк", "Bank"), L("+35 монет и 5% от твоих денег каждую волну", "+35 coins and 5% interest every wave"),
        TowerLevel(240, 35f, 0f, 0f, 0.05f, 80f),
    ),
    ;

    val key: String get() = "b:$name"
}

enum class TargetMode(val title: L) {
    FIRST(L("ПЕРВЫЙ", "FIRST")),
    LAST(L("ПОСЛЕДНИЙ", "LAST")),
    STRONG(L("СИЛЬНЫЙ", "STRONG")),
    CLOSE(L("БЛИЖНИЙ", "CLOSE")),
}

/** Player powers with a cooldown, unlocked during the campaign. */
enum class Ability(val title: L, val hint: L, val cooldown: Float, val unlock: Int) {
    AIRSTRIKE(L("Авиаудар", "Airstrike"), L("Тапни по карте: три бомбы по площади", "Tap the map: three bombs on the spot"), 40f, 2),
    FREEZE(L("Заморозка", "Freeze"), L("Все враги замедлены на 4 секунды", "Slows every enemy for 4 seconds"), 55f, 6),
    REPAIR(L("Ремонт", "Repair"), L("+3 жизни базе", "+3 lives for the base"), 80f, 10),
    ;

    val key: String get() = "a:$name"
}
