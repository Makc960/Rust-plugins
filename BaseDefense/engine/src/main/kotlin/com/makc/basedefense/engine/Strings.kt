package com.makc.basedefense.engine

/** Every line of interface text, in both languages. */
object Tx {
    // Menu
    val TITLE_1 = L("ОБОРОНА", "BASE")
    val TITLE_2 = L("БАЗЫ", "DEFENSE")
    val TAGLINE = L("Удержи базу любой ценой", "Hold the base at any cost")
    val CONTINUE = L("ПРОДОЛЖИТЬ БОЙ", "CONTINUE BATTLE")
    val CONTINUE_SUB = L("%s, волна %d", "%s, wave %d")
    val CAMPAIGN = L("КАМПАНИЯ", "CAMPAIGN")
    val DAILY = L("ЗАДАНИЕ ДНЯ", "DAILY CHALLENGE")
    val DAILY_LOCKED = L("откроется после 2 уровня", "opens after level 2")
    val ENDLESS = L("БЕСКОНЕЧНЫЙ РЕЖИМ", "ENDLESS MODE")
    val ENDLESS_LOCKED = L("откроется после 3 уровня", "opens after level 3")
    val UPGRADES = L("УЛУЧШЕНИЯ", "UPGRADES")
    val CODEX = L("Энциклопедия", "Codex")
    val ACHIEVEMENTS = L("Достижения", "Achievements")
    val SETTINGS = L("Настройки", "Settings")
    val BEST_WAVES = L("Рекорд: %d волн", "Best: %d waves")
    val FREE_POINTS = L("+%d", "+%d")

    // Levels
    val WORLD_LOCKED = L("Сначала пройди «%s»", "Beat %s first")
    val WAVES_COUNT = L("%d волн", "%d waves")
    val NEW_ENEMIES = L("Новые враги", "New enemies")
    val NEW_TOWER = L("Новый отряд", "New squad")
    val NEW_ABILITY = L("Способность", "Ability")
    val DIFFICULTY = L("Сложность", "Difficulty")
    val HARD_LOCKED = L("Сложный режим откроется после победы", "Hard opens after a win")
    val CHALLENGE = L("Испытание", "Challenge")
    val CHALLENGE_HINT = L("Победа с этим условием даёт медаль", "Win with this rule to earn a medal")
    val FIGHT = L("В БОЙ", "FIGHT")

    // Battle
    val WAVE_N = L("ВОЛНА %d", "WAVE %d")
    val START_WAVE = L("НАЧАТЬ ВОЛНУ", "START WAVE")
    val NEXT_WAVE_IN = L("через %d с, бонус +%d", "in %d s, bonus +%d")
    val FINAL = L("ФИНАЛ", "FINAL")
    val FIGHTING = L("ВОЛНА ИДЁТ…", "FIGHTING…")
    val NEXT = L("ДАЛЕЕ", "NEXT")
    val LAST_WAVE = L("Последняя волна!", "Final wave!")
    val UNDO = L("ОТМЕНА", "UNDO")
    val AIM_AIRSTRIKE = L("Тапни по карте для удара", "Tap the map to strike")
    val HINT_PICK = L("Выбери отряд внизу и поставь его у дороги", "Pick a squad below and place it by the road")
    val HINT_DRAG = L("Проведи пальцем по траве у дороги и отпусти", "Drag along the grass by the road and let go")
    val HINT_START = L("Поставь ещё или жми «НАЧАТЬ ВОЛНУ»", "Place more or press START WAVE")
    val ON_ROAD = L("Мины ставятся прямо на дорогу", "Mines go right on the road")
    val TOWER_LIMIT = L("Больше отрядов ставить нельзя: лимит %d", "No more squads: the limit is %d")
    val QUARRY_LIMIT = L("Не больше %d складов", "At most %d supply squads")
    val ROAD = L("дорога", "road")

    // Tower panel
    val UPGRADE = L("УЛУЧШИТЬ %d", "UPGRADE %d")
    val SELL = L("ПРОДАТЬ %d", "SELL %d")
    val SELL_SURE = L("ТОЧНО? +%d", "SURE? +%d")
    val NO_SELLING = L("НЕЛЬЗЯ", "LOCKED")
    val TARGET = L("ЦЕЛЬ", "TARGET")
    val KILLS = L("убито %d", "%d kills")
    val EARNED = L("принёс %d", "earned %d")
    val NEXT_LEVEL = L("Дальше: %s", "Next: %s")
    val CHOOSE_BRANCH = L("Выбери специализацию", "Choose a specialisation")
    val BRANCH_LOCKED = L("Специализации откроются на %d уровне кампании", "Specialisations unlock on campaign level %d")
    val DMG = L("урон %s", "dmg %s")
    val PER_SECOND = L("%s/с", "%s/s")
    val RANGE = L("радиус %s", "range %s")
    val PELLETS = L("дробь %d×%s", "%d×%s pellets")
    val FIRE = L("огонь %s/с", "fire %s/s")
    val BLAST = L("взрыв %s", "blast %s")
    val CHAIN = L("цепь %d", "chain %d")
    val SLOW = L("замедл. %s", "slow %s")
    val STUN = L("оглушение %sс", "stun %ss")
    val COINS_PER_WAVE = L("+%d монет за волну", "+%d coins a wave")
    val AURA = L("урон рядом +%s", "nearby damage +%s")
    val REARM = L("перезарядка %sс", "rearm %ss")
    val PIERCE = L("пробивает %d", "pierces %d")
    val MISSILES = L("ракет %d", "%d missiles")
    val ROCK = L("Камень", "Rock")
    val TREE = L("Дерево", "Tree")
    val CACTUS = L("Кактус", "Cactus")
    val CLEAR_HINT = L("Убери, чтобы строить здесь", "Clear it to build here")
    val CLEAR = L("УБРАТЬ %d", "CLEAR %d")

    // Pause and results
    val PAUSED = L("ПАУЗА", "PAUSED")
    val RESUME = L("ПРОДОЛЖИТЬ", "RESUME")
    val RESTART = L("ЗАНОВО", "RESTART")
    val SETTINGS_BTN = L("НАСТРОЙКИ", "SETTINGS")
    val TO_MENU = L("В МЕНЮ", "MAIN MENU")
    val SAVED_NOTE = L("Бой сохранится, его можно продолжить из меню", "The battle is saved, you can continue it from the menu")
    val VICTORY = L("ПОБЕДА!", "VICTORY!")
    val DEFEAT = L("БАЗА ПАЛА", "BASE LOST")
    val LIVES = L("Жизни: %d/%d", "Lives: %d/%d")
    val KILLED = L("Уничтожено врагов: %d", "Enemies destroyed: %d")
    val WAVE_OF = L("Волна %d из %d", "Wave %d of %d")
    val HELD = L("Продержались", "You held out for")
    val NEW_RECORD = L("Новый рекорд!", "New record!")
    val RECORD = L("Рекорд: %d", "Best: %d")
    val NEXT_LEVEL_BTN = L("ДАЛЬШЕ: %s", "NEXT: %s")
    val AGAIN = L("ЕЩЁ РАЗ", "TRY AGAIN")
    val TO_LEVELS = L("К УРОВНЯМ", "LEVELS")
    val HARD_WIN = L("Победа на сложном", "Hard victory")
    val MEDAL_WON = L("Медаль испытания", "Challenge medal")
    val CHALLENGE_FAILED = L("Испытание не пройдено", "Challenge failed")
    val NEW_ACHIEVEMENTS = L("Новые достижения", "New achievements")
    val DAILY_DONE = L("Задание дня выполнено!", "Daily challenge complete!")
    val STREAK = L("Серия: %d дн.", "Streak: %d days")
    val ACHIEVEMENT_TOAST = L("Достижение: %s", "Achievement: %s")

    // Cards
    val CARD_ENEMY = L("НОВЫЙ ВРАГ", "NEW ENEMY")
    val CARD_TOWER = L("НОВЫЙ ОТРЯД", "NEW SQUAD")
    val CARD_ABILITY = L("НОВАЯ СПОСОБНОСТЬ", "NEW ABILITY")
    val CARD_TIP = L("СОВЕТ", "TIP")
    val GOT_IT = L("ПОНЯТНО", "GOT IT")
    val TIP_BRANCHES = L("Специализации", "Specialisations")
    val TIP_BRANCHES_TEXT = L(
        "Отряд 3 уровня можно превратить в одну из двух специализаций. Выбери ту, что лучше против врагов этого уровня.",
        "A level 3 squad can turn into one of two specialisations. Pick the one that works best against this level's enemies.",
    )
    val TIP_OBSTACLES = L("Камни и деревья", "Rocks and trees")
    val TIP_OBSTACLES_TEXT = L(
        "Нажми на камень, дерево или кактус, чтобы убрать его за монеты и освободить место для отряда.",
        "Tap a rock, tree or cactus to clear it for coins and make room for a squad.",
    )
    val TIP_DESERT = L("Песчаные бури", "Sandstorms")
    val TIP_DESERT_TEXT = L(
        "Иногда налетает буря, и дальность всех отрядов падает на 30%. Ставь отряды поближе к дороге.",
        "Now and then a storm blows in and every squad's range drops by 30%. Dig in close to the road.",
    )
    val TIP_WINTER = L("Метель и лёд", "Blizzards and ice")
    val TIP_WINTER_TEXT = L(
        "В метель отряды стреляют реже, а по льду враги бегут быстрее. Береги ледяные участки дороги.",
        "In a blizzard squads fire slower, and enemies run faster on ice. Guard the icy stretches.",
    )
    val TIP_ZOOM = L("Масштаб", "Zoom")
    val TIP_ZOOM_TEXT = L(
        "Разведи два пальца, чтобы приблизить карту, и веди двумя пальцами, чтобы её двигать.",
        "Spread two fingers to zoom in on the map and drag with two fingers to move it.",
    )

    // Settings
    val LANGUAGE = L("Язык", "Language")
    val SOUND = L("Звуки", "Sound effects")
    val MUSIC = L("Музыка", "Music")
    val VIBRATION = L("Вибрация", "Vibration")
    val LITE = L("Облегчённые эффекты", "Lighter effects")
    val NUMBERS = L("Цифры урона", "Damage numbers")
    val TIPS = L("Подсказки", "Tips")
    val RESET = L("СБРОСИТЬ ПРОГРЕСС", "RESET PROGRESS")
    val RESET_SURE = L("ТОЧНО? НАЖМИ ЕЩЁ РАЗ", "SURE? TAP AGAIN")
    val VERSION = L("Версия %s", "Version %s")
    val SUPPORT = L("ПОДДЕРЖАТЬ АВТОРА", "SUPPORT THE AUTHOR")

    // Perks
    val POINTS = L("Свободно очков: %d", "Points to spend: %d")
    val POINTS_HINT = L(
        "Очки дают звёзды, победы на сложном, медали испытаний и задания дня.",
        "Points come from stars, hard wins, challenge medals and daily wins.",
    )
    val MAX = L("МАКС.", "MAX")
    val RESPEC = L("СБРОСИТЬ ОЧКИ", "REFUND POINTS")

    // Achievements
    val ACH_COUNT = L("%d из %d", "%d of %d")

    // Codex
    val TAB_ENEMIES = L("Враги", "Enemies")
    val TAB_TOWERS = L("Отряды", "Squads")
    val TAB_ABILITIES = L("Способности", "Abilities")
    val NOT_MET = L("Ещё не встречался", "Not met yet")
    val UNLOCKS_ON = L("Откроется на %d уровне", "Unlocks on level %d")
    val HEALTH = L("Здоровье %d", "Health %d")
    val SPEED = L("Скорость %s", "Speed %s")
    val ARMOR = L("Броня %d", "Armor %d")
    val REWARD = L("Награда %d", "Reward %d")
    val BASE_DAMAGE = L("Урон базе %d", "Base damage %d")
    val FLYING = L("Летает", "Flies")
    val BOSS = L("Босс", "Boss")
    val SHIELDED = L("Щит %s", "Shield %s")
    val STEALTHY = L("Невидимка", "Stealthy")
    val COST = L("Цена %d", "Cost %d")
    val BRANCHES = L("Специализации: %s и %s", "Specialisations: %s and %s")
    val COOLDOWN = L("Перезарядка %d с", "Cooldown %d s")
    val TAP_FOR_DETAILS = L("Нажми на карточку, чтобы узнать больше", "Tap a card to learn more")

    // Daily
    val DAILY_MAP = L("Карта: %s", "Map: %s")
    val DAILY_TWISTS = L("Условия дня", "Today's twists")
    val DAILY_BEST = L("Лучшее сегодня: %d из 20 волн", "Today's best: %d of 20 waves")
    val DAILY_WON = L("Сегодня выполнено", "Done for today")
    val DAILY_REWARD = L("Победа приносит очко улучшений, раз в день", "A win gives one upgrade point, once a day")
    val START = L("НАЧАТЬ", "START")

    /** Everything above, for the translation test. */
    val all: List<L>
        get() = Tx::class.java.declaredFields.filter { it.type == L::class.java }.map {
            it.isAccessible = true
            it.get(Tx) as L
        }
}
