package com.makc.basedefense.engine

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both languages are complete and the progress rules hold. */
class I18nTest {
    @AfterTest
    fun russianAgain() {
        I18n.lang = Language.RU
    }

    private fun everything(): List<Pair<String, L>> = buildList {
        for (t in Tx.all) add("Tx" to t)
        for (e in EnemyType.entries) {
            add(e.name to e.title)
            add(e.name to e.hint)
        }
        for (t in TowerType.entries) {
            add(t.name to t.title)
            add(t.name to t.short)
            add(t.name to t.hint)
        }
        for (b in Branch.entries) {
            add(b.name to b.title)
            add(b.name to b.hint)
        }
        for (a in Ability.entries) {
            add(a.name to a.title)
            add(a.name to a.hint)
        }
        for (m in TargetMode.entries) add(m.name to m.title)
        for (d in Difficulty.entries) {
            add(d.name to d.title)
            add(d.name to d.hint)
        }
        for (p in Perk.entries) {
            add(p.name to p.title)
            add(p.name to p.hint)
        }
        for (m in Mod.entries) {
            add(m.name to m.title)
            add(m.name to m.hint)
        }
        for (a in Achievement.entries) {
            add(a.name to a.title)
            add(a.name to a.hint)
        }
        for (b in Biome.entries) add(b.name to b.title)
        for (s in Skill.entries) {
            add(s.name to s.title)
            add(s.name to s.hint)
        }
        for (c in CommanderType.entries) {
            add(c.name to c.title)
            add(c.name to c.role)
            add(c.name to c.weapon)
        }
        for (r in RankPerk.entries) add(r.name to r.text)
        for (n in Weekly.names) add("WEEKLY" to n)
        for (c in Camo.entries) add(c.name to c.title)
        for (l in Levels.all) add("level ${l.id}" to l.name)
    }

    private val placeholder = Regex("%[sd]")

    @Test
    fun everyTextHasBothLanguages() {
        val all = everything()
        assertTrue(Tx.all.size > 100, "found ${Tx.all.size} interface lines")
        for ((owner, l) in all) {
            assertTrue(l.ru.isNotBlank() && l.en.isNotBlank(), "$owner: '${l.ru}' / '${l.en}'")
            assertEquals(placeholder.findAll(l.ru).map { it.value }.toList(), placeholder.findAll(l.en).map { it.value }.toList(), "$owner placeholders: '${l.ru}' / '${l.en}'")
            assertTrue(!l.en.any { it in 'а'..'я' || it in 'А'..'Я' }, "$owner English text has Cyrillic: '${l.en}'")
        }
        // Formatting works in both languages.
        for (lang in Language.entries) {
            I18n.lang = lang
            for ((_, l) in all) {
                // Lines with a plain % sign are only ever shown as they are.
                if (!placeholder.containsMatchIn(l.t)) continue
                val args: Array<Any> = placeholder.findAll(l.t).map<MatchResult, Any> { if (it.value == "%d") 7 else "x" }.toList().toTypedArray()
                l.f(*args)
            }
            for (c in Levels.all.mapNotNull { it.challenge }) assertTrue(c.title.isNotBlank())
        }
    }

    @Test
    fun numbersFollowTheLanguage() {
        I18n.lang = Language.RU
        assertEquals("2,5", Num.one(2.5f))
        assertEquals("3", Num.one(3f))
        assertEquals("35%", Num.percent(0.35f))
        I18n.lang = Language.EN
        assertEquals("2.5", Num.one(2.5f))
    }

    @Test
    fun languageFollowsThePhoneUntilChosen() {
        assertEquals(Language.RU, Profile(SilentPlatform(language = "ru")).language)
        assertEquals(Language.EN, Profile(SilentPlatform(language = "de")).language)
        val p = Profile(SilentPlatform(language = "de"))
        p.language = Language.RU
        assertEquals(Language.RU, p.language)
    }

    @Test
    fun progressPerksAndAchievements() {
        val platform = SilentPlatform()
        val p = Profile(platform)
        assertEquals(1, p.progress)
        assertTrue(p.unlocked(1) && !p.unlocked(2))
        assertTrue(p.recordWin(1, 2, Difficulty.NORMAL, challenge = false))
        assertTrue(!p.recordWin(1, 1, Difficulty.NORMAL, challenge = false), "a worse result changes nothing")
        assertTrue(p.recordWin(1, 3, Difficulty.HARD, challenge = true))
        assertEquals(2, p.progress)
        assertEquals(3 + 1 + 1, p.points, "stars, a hard win and a medal")
        assertTrue(p.buyPerk(Perk.DAMAGE))
        assertEquals(2, p.freePoints)
        assertTrue(!p.buyPerk(Perk.DAMAGE), "not enough points")
        assertEquals(1, p.perks[Perk.DAMAGE])
        p.resetPerks()
        assertEquals(5, p.freePoints)

        p.addStat(Stat.WINS, 1)
        val fresh = p.checkAchievements()
        assertTrue(Achievement.FIRST_WIN in fresh && Achievement.HARD_WIN in fresh)
        assertTrue(p.checkAchievements().isEmpty(), "each achievement only once")
        assertTrue("first_win" in platform.achievements)

        p.orStat(Stat.BRANCHES, 0b101)
        assertEquals(2, Integer.bitCount(p.stat(Stat.BRANCHES)))

        // Daily: a streak needs wins on days in a row.
        p.recordDaily(100, 20, won = true)
        p.recordDaily(101, 20, won = true)
        p.recordDaily(101, 20, won = true)
        assertEquals(2, p.dailyStreak(101))
        assertEquals(2, p.stat(Stat.DAILY_WINS), "one point per day")
        p.recordDaily(103, 20, won = true)
        assertEquals(1, p.dailyStreak(103))
        assertEquals(0, p.dailyStreak(105), "a missed day breaks the streak")

        p.save = "something"
        p.resetProgress()
        assertEquals(1, p.progress)
        assertEquals(0, p.points)
        assertTrue(!p.achieved(Achievement.FIRST_WIN))
        assertEquals(null, p.save)
    }

    @Test
    fun oldSettingsCarryOver() {
        val platform = SilentPlatform()
        platform.putInt("sound", 0)
        assertEquals(0, Profile(platform).sound, "sound switched off in the first version stays off")
        assertEquals(80, Profile(SilentPlatform()).sound)
    }
}
