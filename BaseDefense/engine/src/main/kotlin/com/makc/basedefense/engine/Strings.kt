package com.makc.basedefense.engine

/** Every line of interface text, in both languages. */
object Tx {
    // Menu
    val TITLE_1 = L("ОБОРОНА", "BASE")
    val TITLE_2 = L("БАЗЫ", "DEFENSE")
    val TAGLINE = L("Удержи базу любой ценой", "Hold the base at any cost")
    val INTRO_COMMANDERS = L("Командиры ждут приказа", "Commanders await orders")
    val INTRO_LOADING = L("ЗАГРУЗКА", "LOADING")
    val INTRO_READY = L("К БОЮ!", "TO BATTLE!")
    val CONTINUE = L("ПРОДОЛЖИТЬ БОЙ", "CONTINUE BATTLE")
    val CONTINUE_SUB = L("%s, волна %d", "%s, wave %d")
    val CAMPAIGN = L("КАМПАНИЯ", "CAMPAIGN")
    val DAILY = L("ЗАДАНИЕ ДНЯ", "DAILY CHALLENGE")
    val DAILY_LOCKED = L("откроется после 2 уровня", "opens after level 2")
    val ENDLESS = L("БЕСКОНЕЧНЫЙ РЕЖИМ", "ENDLESS MODE")
    val ENDLESS_LOCKED = L("откроется после 3 уровня", "opens after level 3")
    val UPGRADES = L("УЛУЧШЕНИЯ", "UPGRADES")
    val COMMANDERS = L("КОМАНДИРЫ", "COMMANDERS")
    val COMMANDERS_SUB = L("в бою: %s", "in battle: %s")
    val CODEX = L("Энциклопедия", "Codex")
    val TUTORIAL = L("Обучение", "Tutorial")
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
    val RAIL_LIMIT = L("Не больше %d рельсотронов", "At most %d railguns")
    val RAIL_VIP = L("Рельсотрон — только для VIP", "The railgun is for VIP only")
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
    val THROUGH = L("насквозь", "pierces all")
    val HALF_ARMOR = L("броня ½", "armor ½")
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
    val WATCH_AD = L("СМОТРЕТЬ РЕКЛАМУ", "WATCH A VIDEO")
    val AD_LOADING = L("Реклама загружается…", "The video is loading…")
    val AD_NOT_WATCHED = L("Награда — только за просмотр до конца", "The reward comes only after the whole video")
    val AD_REWARD = L("+%d за рекламу", "+%d for a video")
    val AD_POINT = L("+%d ЗА РЕКЛАМУ", "+%d FOR A VIDEO")
    val AD_WAIT = L("ЗАГРУЗКА…", "LOADING…")
    val LAST_CHANCE = L("ПОСЛЕДНИЙ ШАНС", "LAST CHANCE")
    val REVIVE_TEXT = L(
        "Посмотри короткую рекламу — резерв принесёт %d жизней, и бой продолжится. Такой шанс один на бой.",
        "Watch a short video and the reserves bring %d lives, and the fight goes on. One such chance a battle.",
    )
    val REVIVE_SUB = L("+%d жизней, бой продолжится", "+%d lives, the fight goes on")
    val GIVE_UP = L("СДАТЬСЯ", "GIVE UP")
    val DOUBLE_SEASON = L("УДВОИТЬ ОЧКИ СЕЗОНА", "DOUBLE SEASON POINTS")
    val TIP_VOLCANO = L("Извержения", "Eruptions")
    val TIP_VOLCANO_TEXT = L(
        "Во время извержения падают лавовые бомбы: они жгут врагов, но иногда на минуту накрывают и отряд. Саламандр в огнеупорных костюмах огонь не берёт — против них нужны пули, ракеты и мороз.",
        "In an eruption lava bombs fall: they burn enemies but now and then knock out a squad for a moment. Fire does not hurt salamanders in their fire suits; bring bullets, rockets and frost.",
    )
    val TIP_RUINS = L("Засады", "Ambushes")
    val TIP_RUINS_TEXT = L(
        "В городе враги идут по двум дорогам сразу, а во время засады вылезают из подвалов посреди дороги — красный круг покажет где. Держи отряды не только у края карты. Огнемётные танки выжигают мины, а байкеров замедление держит вполовину.",
        "In the city enemies come down two roads at once, and in an ambush they climb out of cellars halfway along a road; a red ring shows where. Keep squads away from the edge too. Flame tanks burn out mines, and slowing holds bikers only half as well.",
    )
    val TIP_COMMANDER = L("Командир", "Commander")
    val TIP_COMMANDER_TEXT = L(
        "Нажми на командира или на его карточку внизу, потом на карту — он пойдёт туда. Стреляет он сам и растёт в уровне прямо в бою, а навыки — в его панели.",
        "Tap the commander or his card below, then the map, and he walks there. He fires on his own and levels up during the battle; his skills are in his panel.",
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
    val FIREPROOF = L("Не горит", "Fireproof")
    val BURROWS = L("Роет под землёй", "Burrows")
    val UNSTOPPABLE = L("Не остановить", "Unstoppable")
    val NIMBLE = L("Вёрткий", "Nimble")
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
    val DAILY_REWARD_VIP = L("Победа приносит 2 очка улучшений (VIP), раз в день", "A win gives 2 upgrade points (VIP), once a day")

    // VIP
    val VIP = L("VIP", "VIP")
    val VIP_TITLE = L("VIP-СТАТУС", "VIP STATUS")
    val VIP_PRICE = L("%d ₽ в месяц", "%d ₽ a month")
    val VIP_PRICE_DAYS = L("%d ₽ за 30 дней", "%d ₽ for 30 days")
    val VIP_ACTIVE = L("Активен до %s", "Active until %s")
    val VIP_WITHOUT = L("Без VIP", "Without")
    val VIP_ROW_START = L("Монеты на старте боя", "Starting coins")
    val VIP_ROW_BOUNTY = L("Монеты за врагов", "Coins per kill")
    val VIP_ROW_COOLDOWN = L("Перезарядка способностей", "Ability recharge")
    val VIP_ROW_SPEED = L("Скорость боя", "Battle speed")
    val VIP_ROW_UNDO = L("Отмена постройки", "Undo a new squad")
    val VIP_ROW_DAILY = L("Очки за задание дня", "Points for the daily")
    val VIP_ROW_CROWN = L("Корона в меню и в бою", "Crown in menus and battle")
    val VIP_UP_TO = L("до ×%d", "up to ×%d")
    val VIP_SECONDS = L("%d с", "%d s")
    val VIP_BUY = L("ОФОРМИТЬ VIP", "GET VIP")
    val VIP_ELSEWHERE = L("в версии игры из RuStore", "in the game's RuStore version")
    val VIP_FOREVER = L("VIP НАВСЕГДА", "VIP FOREVER")
    val VIP_ONCE = L("%d ₽ один раз, без подписки", "%d ₽ once, no subscription")
    val VIP_ACTIVE_FOREVER = L("Активен навсегда", "Active forever")
    val VIP_ROW_COMMANDER = L("Командир в начале боя", "Commander at the start")
    val VIP_LEVEL = L("ур. %d", "lv %d")
    val VIP_ROW_COMMANDERS = L("Все командиры", "Every commander")
    val VIP_ROW_RESERVE = L("Второй шанс в бою", "Second chance")
    val VIP_ROW_XP = L("Опыт командира", "Commander experience")
    val VIP_ROW_GIFT = L("Подарок каждый день", "A gift every day")
    val VIP_ROW_RAIL = L("Сверхоружие: рельсотрон", "Superweapon: railgun")
    val RAIL_NOTE = L("Без специализаций: три уровня, и каждый — огромный урон. Не больше %d на карте.", "No specialisations: three levels, each with huge damage. At most %d on a map.")
    val VIP_LIVES = L("+%d жизней", "+%d lives")
    val VIP_GIFT = L("Подарок VIP: +%d очко улучшений", "VIP gift: +%d upgrade point")
    val VIP_OPEN = L("Открыт, пока действует VIP", "Yours while VIP lasts")

    // Pop-ups
    val PROMO_RAIL = L("Рельсотрон — самое мощное оружие в игре", "The railgun, the mightiest weapon in the game")
    val PROMO_1 = L("Все командиры в твоём распоряжении", "Every commander at your command")
    val PROMO_2 = L("Второй шанс: +%d жизней, когда база пала", "A second chance: +%d lives when the base falls")
    val PROMO_3 = L("+%d%% монет на старте и +%d%% за врагов", "+%d%% starting coins and +%d%% per kill")
    val PROMO_4 = L("Бой до ×%d и подарок каждый день", "Battles up to ×%d and a gift every day")
    val PROMO_MORE = L("ПОДРОБНЕЕ", "LEARN MORE")
    val PROMO_LATER = L("НЕ СЕЙЧАС", "NOT NOW")
    val ENDED_TITLE = L("VIP ЗАКОНЧИЛСЯ", "VIP HAS ENDED")
    val ENDED_TEXT = L(
        "Продли VIP, чтобы вернуть второй шанс, бонусные монеты и всех командиров.",
        "Renew VIP to get back the second chance, the bonus coins and every commander.",
    )
    val ENDED_RENEW = L("ПРОДЛИТЬ", "RENEW")
    val DEFEAT_VIP = L("С VIP был бы второй шанс: +%d жизней", "With VIP you'd get a second chance: +%d lives")
    val WELCOME_TITLE = L("ДОБРО ПОЖАЛОВАТЬ!", "WELCOME!")
    val WELCOME_TEXT = L(
        "Пройди короткое обучение: покажем, как ставить отряды, улучшать их и вести командира в бой.",
        "Take a short tutorial: we'll show you how to place squads, upgrade them and lead your commander.",
    )
    val WELCOME_GO = L("ПРОЙТИ ОБУЧЕНИЕ", "START TUTORIAL")
    val WELCOME_SKIP = L("ПРОПУСТИТЬ", "SKIP")

    // Tutorial
    val TUT_LABEL = L("ОБУЧЕНИЕ %d/%d", "TUTORIAL %d/%d")
    val TUT_NEXT = L("ДАЛЕЕ", "NEXT")
    val TUT_INTRO = L("Это учебный бой. Враги идут по дороге к твоей базе. Не дай им дойти!", "A training battle. Enemies walk the road to your base. Don't let them through!")
    val TUT_PICK = L("Выбери отряд внизу, например «Стрелки».", "Pick a squad below, say the Riflemen.")
    val TUT_PLACE = L("Нажми на подсвеченную клетку травы у дороги.", "Tap the highlighted grass by the road.")
    val TUT_START = L("Нажми «НАЧАТЬ ВОЛНУ». Отряды стреляют сами.", "Press START WAVE. Squads fire on their own.")
    val TUT_WATCH = L("За каждого врага дают монеты. Дождись конца волны.", "Every enemy pays coins. Wait for the wave to end.")
    val TUT_SELECT = L("Нажми на свой отряд на карте.", "Tap your squad on the map.")
    val TUT_UPGRADE = L("Нажми «УЛУЧШИТЬ»: отряд станет сильнее.", "Press UPGRADE to make the squad stronger.")
    val TUT_HERO = L("Это твой командир. Нажми на него.", "This is your commander. Tap him.")
    val TUT_MOVE = L("Теперь нажми на карту у дороги: командир пойдёт туда.", "Now tap the map by the road and he walks there.")
    val TUT_WAVE2 = L("Начни следующую волну.", "Start the next wave.")
    val TUT_SKILL = L("Когда враги подойдут к командиру, нажми «ГРАНАТА» в его панели.", "When enemies come near the commander, press GRENADE in his panel.")
    val TUT_STRIKE = L("Авиаудар: нажми кнопку слева внизу, потом на врагов.", "Airstrike: press the button at the bottom left, then tap the enemies.")
    val TUT_SPEED = L("Кнопка ×1 ускоряет бой. Нажми на неё.", "The ×1 button speeds the battle up. Press it.")
    val TUT_FINISH = L("Отбей последние волны! Ставь ещё отряды и улучшай их.", "Hold off the last waves! Place more squads and upgrade them.")
    val TUT_DONE = L("ОБУЧЕНИЕ ПРОЙДЕНО", "TUTORIAL COMPLETE")
    val TUT_DONE_TEXT = L("Ты готов к бою, командир!", "You're ready for battle, commander!")
    val TO_CAMPAIGN = L("В КАМПАНИЮ", "TO THE CAMPAIGN")
    val VIP_NOTE = L(
        "Подписка оформляется через RuStore и продлевается каждый месяц. Отменить её можно в любой момент в RuStore, в разделе «Подписки».",
        "The subscription goes through RuStore and renews every month. You can cancel it any time in RuStore, under Subscriptions.",
    )
    val VIP_NOTE_DAYS = L(
        "Оплата картой или через СБП на странице Pally. VIP на месяц — это 30 дней без автопродления: сам ничего не спишется.",
        "Pay by card or SBP on Pally's page. Monthly VIP is 30 days that do not renew: nothing is ever charged by itself.",
    )
    val VIP_THANKS = L("VIP включён. Спасибо за поддержку!", "VIP is on. Thank you for the support!")

    // Shop
    val BOUGHT = L("Покупка прошла. Спасибо за поддержку!", "Purchase complete. Thank you for the support!")
    val PAY_FAILED = L("Оплата не прошла", "The payment did not go through")
    val PAY_UNAVAILABLE = L("Оплата сейчас недоступна. Попробуйте позже", "Payments are unavailable right now. Try again later")
    val CODE_LABEL = L("КОД ПОКУПОК", "PURCHASE CODE")
    val CODE_TEXT = L(
        "Покупки хранятся под этим кодом. Запишите его: на новом телефоне введите код, и всё купленное перенесётся. Нажмите на код, чтобы скопировать.",
        "Your purchases are kept under this code. Write it down: enter it on a new phone and everything you bought moves there. Tap the code to copy it.",
    )
    val CODE_ENTER = L("ВВЕСТИ КОД С ДРУГОГО ТЕЛЕФОНА", "ENTER A CODE FROM ANOTHER PHONE")
    val CODE_TITLE = L("Код покупок", "Purchase code")
    val CODE_HINT = L("XXXX-XXXX-XXXX-XXXX-XXXX", "XXXX-XXXX-XXXX-XXXX-XXXX")
    val CODE_GO = L("Перенести", "Move")
    val CODE_WRONG = L("Код не подошёл или нет связи с магазином", "Wrong code, or the shop is out of reach")
    val CODE_TAKEN = L("Код принят. Покупок под ним пока нет", "Code accepted. Nothing bought under it yet")
    val CODE_COPIED = L("Код скопирован", "Code copied")
    val RESTORE = L("ВОССТАНОВИТЬ ПОКУПКИ", "RESTORE PURCHASES")
    val RESTORED = L("Покупки восстановлены", "Purchases restored")
    val NOT_FOUND = L("Покупки не найдены", "No purchases found")
    val PRICE = L("%d ₽", "%d ₽")
    val BUY = L("КУПИТЬ", "BUY")
    val STARTER = L("НАБОР НОВОБРАНЦА", "RECRUIT PACK")
    val STARTER_TEXT = L("Инженер Ковалёва и %d очков улучшений. Только один раз", "Engineer Kovaleva and %d upgrade points. Once only")
    val STARTER_BUY = L("ВЗЯТЬ НАБОР", "GET THE PACK")

    // Commanders
    val IN_BATTLE = L("В БОЮ", "IN BATTLE")
    val TAKE = L("ВЗЯТЬ В БОЙ", "TAKE INTO BATTLE")
    val FREE_FOR_STARS = L("Бесплатно за %d звёзд кампании, у тебя %d", "Free for %d campaign stars, you have %d")
    val SKILLS = L("Навыки", "Skills")
    val GROWS = L("В бою растёт до 5 уровня: урон, дальность и сила навыков", "Grows to level 5 in battle: damage, range and skill power")
    val GROUND_AIR = L("по земле и воздуху", "ground and air")
    val GROUND_ONLY = L("только по земле", "ground only")
    val LEVEL_SHORT = L("УР. %d", "LV %d")
    val SEND_HINT = L("Тапни по карте — командир пойдёт туда", "Tap the map to send the commander there")
    val JAMMED = L("Заглушён сапёром", "Jammed by a sapper")
    val NO_TARGET = L("Рядом некого бить", "No one within reach")
    val SKILLS_OFF = L("Навыки отключены", "Skills are off")
    val SECONDS = L("%s с", "%s s")
    val SERVICE = L("Опыт командира: +%d", "Commander's service: +%d")
    val RANK_UP = L("Новый ранг: %d", "New rank: %d")
    val RANK = L("РАНГ %d", "RANK %d")
    val RANK_SHORT = L("РАНГ %d", "RANK %d")
    val RANK_NEXT = L("Ранг %d: %s", "Rank %d: %s")
    val RANK_TOP = L("Высший ранг", "Top rank")
    val SKILL_FROM_RANK = L("с %d ранга", "from rank %d")
    val STUN_FOR = L("оглушение %s с", "stun %s s")

    // Missions: the daily challenge and the weekly operation
    val MISSIONS = L("ЗАДАНИЯ", "MISSIONS")
    val MISSIONS_SUB = L("задание дня, операция недели и приказы", "daily challenge, weekly operation and orders")
    val TAB_DAY = L("ДЕНЬ", "DAY")
    val TAB_WEEK = L("НЕДЕЛЯ", "WEEK")
    val TAB_ORDERS = L("ПРИКАЗЫ", "ORDERS")
    val TAB_BOSSES = L("БОССЫ", "BOSSES")
    val BOSS_RUSH = L("Штурм боссов", "Boss rush")
    val BOSS_RUSH_ABOUT = L(
        "%d волн одних боссов с охраной. Чем быстрее последний упадёт, тем лучше медаль — вызывай волны досрочно!",
        "%d waves of bosses and their escorts. The sooner the last one falls, the better the medal: call the waves early!",
    )
    val BOSS_RUSH_LOCKED = L("Штурм боссов откроется после %d уровня кампании", "The boss rush opens after campaign level %d")
    val BOSS_LINEUP = L("Боссы сегодня", "Today's bosses")
    val BOSS_MEDALS = L("Медали за время", "Medals for time")
    val BOSS_UNDER = L("Быстрее %s", "Under %s")
    val BOSS_BEST = L("Лучшее время сегодня: %s", "Today's best time: %s")
    val BOSS_NOT_YET = L("Сегодня ещё не пройден", "Not won today yet")
    val BOSS_TIME = L("Время: %s", "Time: %s")
    val BOSS_DONE = L("Боссы разбиты", "The bosses are beaten")
    val BOSS_RECORD = L("Лучшее время дня!", "The day's best time!")
    val BOSS_DAY_BEST = L("Лучшее за день: %s", "Best today: %s")
    val ORDERS_TITLE = L("ПРИКАЗЫ ДНЯ", "TODAY'S ORDERS")
    val ORDERS_SUB = L("Новые приказы каждый день. Считаются все бои, кроме учебного.", "New orders every day. Every battle counts but the training one.")
    val ORDERS_BONUS = L("Все три приказа", "All three orders")
    val ORDER_TAKEN = L("Получено", "Taken")
    val ORDER_DONE = L("Приказ выполнен: %s", "Order carried out: %s")
    val SUPPLY_TITLE = L("СНАБЖЕНИЕ", "SUPPLIES")
    val SUPPLY_TEXT = L("Заходи каждый день — награды растут, а на седьмой день VIP на сутки и 2 очка улучшений.", "Come back every day: the rewards grow, and on the seventh day there is a day of VIP and 2 upgrade points.")
    val SUPPLY_DAY = L("День %d из %d", "Day %d of %d")
    val SUPPLY_TOMORROW = L("Завтра: %s", "Tomorrow: %s")
    val SUPPLY_GOT = L("Снабжение: %s", "Supplies: %s")
    val REWARD_SEASON = L("очки сезона", "season points")
    val WEEKLY_NAME = L("Операция «%s»", "Operation %s")
    val WEEKLY_LOCKED = L("Операция недели откроется после %d уровня кампании", "The weekly operation opens after campaign level %d")
    val WEEKLY_DAYS = L("До новой операции: %d дн.", "Next operation in %d d")
    val WEEKLY_BOSSES = L("Боссы на %d и %d волне", "Bosses on waves %d and %d")
    val WEEKLY_MEDALS = L("Медали недели", "This week's medals")
    val MEDAL_RULES = listOf(
        L("Победа", "A win"),
        L("Половина жизней", "Half the lives kept"),
        L("Девять десятых жизней", "Nine tenths kept"),
    )
    val MEDALS = listOf(L("бронза", "bronze"), L("серебро", "silver"), L("золото", "gold"))
    val WEEKLY_REWARD = L("+%d к очкам улучшений", "+%d upgrade points")
    val WEEKLY_DONE = L("Операция выполнена", "Operation complete")
    val WEEKLY_MEDAL = L("Медаль: %s", "Medal: %s")
    val POINTS_GAINED = L("Очки улучшений: +%d", "Upgrade points: +%d")

    // Season pass
    val PASS = L("ПРОПУСК СЕЗОНА", "SEASON PASS")
    val PASS_BADGE = L("СЕЗОН · %d", "SEASON · %d")
    val PASS_SEASON = L("Сезон «%s»", "The %s season")
    val PASS_DAYS = L("До конца сезона: %d дн.", "%d days left in the season")
    val PASS_TIER_TAG = L("УРОВЕНЬ %d", "TIER %d")
    val PASS_FREE = L("Бесплатно", "Free")
    val PASS_PREMIUM = L("Премиум", "Premium")
    val PASS_BUY = L("ОТКРЫТЬ ПРЕМИУМ", "GET PREMIUM")
    val PASS_BUY_TEXT = L("Награда на каждом уровне: дни VIP, опыт командиров, городской камуфляж", "A reward on every tier: VIP days, commanders' service, the urban camouflage")
    val PASS_ON = L("ПРЕМИУМ ОТКРЫТ", "PREMIUM ON")
    val PASS_HOW = L("Очки сезона дают любые бои; у VIP их на 50% больше", "Every battle brings season points; VIP gets 50% more")
    val PASS_TAKE = L("ЗАБРАТЬ", "TAKE")
    val PASS_CLAIMED = L("Награда получена", "Reward received")
    val REWARD_POINTS = L("очки улучшений", "upgrade points")
    val REWARD_SERVICE = L("опыт командира", "commander's service")
    val REWARD_VIP = L("на %d дн.", "for %d days")
    val REWARD_CAMO = L("камуфляж", "camouflage")
    val SEASON_XP = L("Очки сезона: +%d", "Season points: +%d")
    val PASS_TIER = L("Уровень пропуска: %d", "Pass tier: %d")
    val VIP_ROW_SEASON = L("Очки сезона", "Season points")

    // Looks
    val WARDROBE = L("ОБЛИКИ", "LOOKS")
    val WARDROBE_BTN = L("Облики", "Looks")
    val CAMO_SQUADS = L("Маскировка отрядов", "Squads' camouflage")
    val CAMO_COMMANDERS = L("Форма командиров", "Commanders' uniforms")
    val CAMO_PICKED = L("ВЫБРАНА", "ON")
    val CAMO_PICK = L("ВЫБРАТЬ", "WEAR")
    val CAMO_WORLD = L("Пройди все уровни мира «%s»: %d из %d", "Win every level of %s: %d of %d")
    val CAMO_PASS = L("Награда пропуска сезона", "A season pass reward")
    val CAMO_VIP = L("или бесплатно с VIP", "or free with VIP")
    val CAMO_SET = L("Набор «%s»: форма всех командиров и маскировка отрядов", "The %s set: every commander's uniform and the squads' camouflage")
    val CAMO_PARADE = L("Парадная: набор, VIP или 10 ранг командира", "Parade: the set, VIP or the commander's rank 10")
    val VIP_ROW_PARADE = L("Парадная форма и маскировка", "Parade uniform and camouflage")

    // Reminders, rating and updates
    val REMINDERS = L("Напоминания", "Reminders")
    val REMINDERS_TITLE = L("НАПОМИНАНИЯ", "REMINDERS")
    val REMINDERS_TEXT = L(
        "Сообщу, когда выйдет новое задание дня, и не дам сгореть серии. Не чаще раза в день.",
        "I'll tell you when a new daily challenge is out and before a streak burns. Once a day at most.",
    )
    val REMINDERS_ON = L("ВКЛЮЧИТЬ", "TURN ON")
    val REMINDERS_NO = L("Не надо", "No, thanks")
    val REMINDERS_BLOCKED = L("Разреши игре уведомления в настройках телефона", "Allow the game's notifications in the phone's settings")
    val REMIND_STREAK = L("Серия заданий дня: %d", "Daily streak: %d")
    val REMIND_STREAK_TEXT = L("Пройди задание дня до полуночи, иначе серия сгорит.", "Win the daily challenge before midnight or the streak burns.")
    val REMIND_DAILY = L("Новое задание дня", "A new daily challenge")
    val REMIND_DAILY_TEXT = L("Сегодня: %s. Удержишь базу?", "Today: %s. Can you hold the base?")
    val REMIND_DAILY_VIP = L("Сегодня: %s. А в штабе ждёт подарок VIP.", "Today: %s. And a VIP gift waits at HQ.")
    val REMIND_CAMPAIGN = L("Командир, база ждёт приказов", "Commander, the base awaits orders")
    val REMIND_CAMPAIGN_TEXT = L("Уровень %d ещё не взят. Вперёд!", "Level %d is still to be won. Forward!")
    val REMIND_BACK = L("Враги у ворот базы!", "Enemies at the gates!")
    val REMIND_BACK_TEXT = L("Отряды ждут командира. Возвращайся в бой.", "Your squads await their commander. Back to battle.")
    val PROMO = L("ПРОМОКОД", "PROMO CODE")
    val PROMO_TITLE = L("Промокод", "Promo code")
    val PROMO_HINT = L("Введи код", "Enter the code")
    val PROMO_GO = L("Активировать", "Redeem")
    val PROMO_DONE = L("Промокод принят: %s", "Code redeemed: %s")
    val PROMO_USED = L("Этот промокод уже активирован", "This code is already redeemed")
    val PROMO_EXPIRED = L("Срок этого промокода закончился", "This code has expired")
    val PROMO_UNKNOWN = L("Такого промокода нет", "No such code")
    val GIFT_POINTS = L("+%d очков улучшений", "+%d upgrade points")
    val GIFT_SERVICE = L("+%d опыта командиру", "+%d commander service")
    val GIFT_VIP = L("VIP на %d дн.", "VIP for %d days")
    val ADMIN_MODE = L("Режим администратора", "Admin mode")
    val ADMIN_ON = L("Режим администратора: открыто всё", "Admin mode: everything is open")
    val ADMIN_OFF = L("Режим администратора выключен", "Admin mode is off")
    val NEWS_TITLE = L("ЧТО НОВОГО В %s", "WHAT'S NEW IN %s")
    val NEWS = listOf(
        L("Новый мир «Руины города»: 8 уровней на двух дорогах, засады, байкеры, огнемётные танки и босс-дирижабль", "A new world, the City Ruins: 8 levels on two roads, ambushes, bikers, flame tanks and an airship boss"),
        L("Штурм боссов на время: вкладка «Боссы» в заданиях", "A boss rush against the clock: the Bosses tab of the missions"),
        L("Четвёртый командир — лейтенант Соколова, снайпер и медик", "A fourth commander: lieutenant Sokolova, a sniper and a medic"),
        L("Новая оркестровая музыка и звуки, сюжет в картинках и приказы штаба", "New orchestral music and sounds, a story in pictures and daily orders"),
    )
    val NEWS_GO = L("В КАМПАНИЮ", "TO THE CAMPAIGN")
    val UPDATE_TITLE = L("НОВАЯ ВЕРСИЯ", "NEW VERSION")
    val UPDATE_TEXT = L("Вышло обновление игры. Поставь его, чтобы получить всё новое.", "An update of the game is out. Install it to get everything new.")
    val UPDATE_GO = L("ОБНОВИТЬ", "UPDATE")

    // The backstory
    val STORY_TITLE = L("СЮЖЕТ", "STORY")
    val STORY_SKIP = L("ПРОПУСТИТЬ", "SKIP")
    val STORY_NEXT = L("ДАЛЕЕ", "NEXT")
    val STORY_GO = L("К БОЮ!", "TO BATTLE!")
    val STORY_CHAPTERS = listOf(
        L("ДО ВОЙНЫ", "BEFORE THE WAR"),
        L("ОРДА", "THE HORDE"),
        L("БРОНЯ", "ARMOUR"),
        L("ПОСЛЕДНИЙ РУБЕЖ", "THE LAST LINE"),
        L("ТВОЙ ПРИКАЗ", "YOUR ORDERS"),
    )
    val STORY_VALLEY_TITLES = listOf(L("ДОЛИНА УДЕРЖАНА", "THE VALLEY HOLDS"), L("СЛЕД В ПЕСКАХ", "TRACKS IN THE SAND"))
    val STORY_VALLEY_TEXTS = listOf(
        L(
            "Долина удержана. Орда откатилась на юг, бросив разбитые машины и раненых.",
            "The valley holds. The Horde fell back south, leaving its wrecks and its wounded behind.",
        ),
        L(
            "Разведчики нашли её след: в пустыне Орда собирает новое войско. Сержант ведёт отряды на юг.",
            "Scouts found its tracks: in the desert the Horde is gathering a new army. The sergeant leads the squads south.",
        ),
    )
    val STORY_DESERT_TITLES = listOf(L("ПЕСКИ ПОЗАДИ", "THE SANDS BEHIND"), L("ЗИМНИЙ ПОХОД", "THE WINTER MARCH"))
    val STORY_DESERT_TEXTS = listOf(
        L(
            "Пустыня наша. Пленные твердят одно: главные силы Орды ушли на север, к перевалам.",
            "The desert is ours. The prisoners all say the same: the Horde's main force went north, to the passes.",
        ),
        L(
            "Там, во льдах, её прикрывают щитоносцы и медики. Зима будет долгой.",
            "Up there in the ice, shieldbearers and medics cover it. It will be a long winter.",
        ),
    )
    val STORY_NORTH_TITLES = listOf(L("ПЕРЕВАЛЫ ВЗЯТЫ", "THE PASSES ARE TAKEN"), L("ОГНЕННАЯ ЗЕМЛЯ", "THE LAND OF FIRE"))
    val STORY_NORTH_TEXTS = listOf(
        L(
            "Перевалы взяты. Но земля дрожит: за горами проснулся вулкан.",
            "The passes are taken. But the ground is shaking: beyond the mountains a volcano has woken.",
        ),
        L(
            "Из огня выходят титаны и саламандры. Там, у жерла, последнее логово Орды.",
            "Titans and salamanders walk out of the fire. There, by the crater, is the Horde's last lair.",
        ),
    )
    val STORY_VOLCANO_TITLES = listOf(L("ТИТАНЫ ПАЛИ", "THE TITANS FALL"), L("ГОРОД В ОГНЕ", "THE CITY ABLAZE"))
    val STORY_VOLCANO_TEXTS = listOf(
        L(
            "Последний титан рухнул в лаву. Но вожаки Орды ушли от погони — и не в горы.",
            "The last titan fell into the lava. But the Horde's leaders got away, and not into the mountains.",
        ),
        L(
            "Они ударили по городу, который мы защищали. Улицы в руинах, в подвалах засады, над крышами плывут дирижабли. Сержант ведёт отряды в город.",
            "They struck the very city we were guarding. Its streets are in ruins, ambushes wait in the cellars, airships float over the roofs. The sergeant leads the squads into the city.",
        ),
    )
    val STORY_RUINS_TITLES = listOf(L("ГОРОД СВОБОДЕН", "THE CITY IS FREE"), L("ДОМОЙ", "HOME"))
    val STORY_RUINS_TEXTS = listOf(
        L(
            "Последний дирижабль рухнул на площадь. Вожаки Орды сдались — война окончена.",
            "The last airship crashed onto the square. The Horde's leaders gave themselves up: the war is over.",
        ),
        L(
            "Над базой у Побережья снова тихо. Но сержант не снимает часовых: мир тоже надо уметь удержать.",
            "It is quiet again over the base on the Coast. But the sergeant keeps the sentries posted: peace has to be held too.",
        ),
    )
    val STORY_TEXTS = listOf(
        L(
            "Много лет наша база у Побережья стерегла единственную дорогу к городу. Служба была тихой: дозоры, учения, чай у костра.",
            "For years our base on the Coast guarded the only road to the city. Duty was quiet: patrols, drills, tea by the fire.",
        ),
        L(
            "Потом из пустыни пришла Орда — налётчики, мутанты и громилы. Посёлок за посёлком падал перед ней.",
            "Then the Horde came out of the desert: raiders, mutants and brutes. Town after town fell before it.",
        ),
        L(
            "За Ордой шли броневики и вертолёты. Армия отступила за горы, и между врагом и городом осталась только наша база.",
            "Armoured cars and helicopters followed the Horde. The army fell back beyond the mountains, and only our base stood between the enemy and the city.",
        ),
        L(
            "Сержант Волков собрал добровольцев: «Окопаться у дороги! Ни шагу назад — за нами город».",
            "Sergeant Volkov gathered volunteers: \"Dig in by the road! Not one step back: the city is behind us.\"",
        ),
        L(
            "Теперь командуешь ты. Расставляй отряды, улучшай их, вызывай авиаудары — и ни один враг не должен дойти до базы.",
            "Now you are in command. Place squads, upgrade them, call in airstrikes, and let no enemy reach the base.",
        ),
    )

    /** Everything above, for the translation test. */
    val all: List<L>
        get() = Tx::class.java.declaredFields.flatMap {
            it.isAccessible = true
            when (val v = it.get(Tx)) {
                is L -> listOf(v)
                is List<*> -> v.filterIsInstance<L>()
                else -> emptyList()
            }
        }
}
