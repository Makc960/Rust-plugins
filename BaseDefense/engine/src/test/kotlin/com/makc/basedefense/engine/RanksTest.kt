package com.makc.basedefense.engine

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Commanders' ranks between battles, what they bring, and the third skills. */
class RanksTest {
    private val step = 1f / 60f

    private fun world(type: CommanderType, rank: Int): World {
        val w = World(Levels.all[0], Rules(progress = 10, commander = type, rank = rank))
        w.beginForTest()
        return w
    }

    private fun run(w: World, seconds: Float) {
        var t = 0f
        while (t < seconds) {
            w.step(step)
            t += step
        }
    }

    /** How far along the first road its point nearest to (x, y) is. */
    private fun distNear(w: World, x: Float, y: Float): Float {
        val path = w.map.paths[0]
        val p = FloatArray(4)
        var best = 0f
        var bestD = Float.MAX_VALUE
        var d = 0f
        while (d <= path.length) {
            path.pointAt(d, p)
            val dd = hypot(p[0] - x, p[1] - y)
            if (dd < bestD) {
                bestD = dd
                best = d
            }
            d += 0.05f
        }
        return best
    }

    private fun nextTo(w: World, type: EnemyType, hpMul: Float = 1f): Enemy {
        val c = w.commander!!
        val e = w.spawnForTest(type, distNear(w, c.x, c.y), hpMul = hpMul)
        e.stunTimer = 1000f
        return e
    }

    @Test
    fun serviceBringsRanksAndRanksBringPerks() {
        val p = Profile(SilentPlatform())
        assertEquals(1, p.rank(CommanderType.SERGEANT))
        assertEquals(0, p.addService(CommanderType.SERGEANT, Ranks.SERVICE[2] - 1))
        assertEquals(2, p.addService(CommanderType.SERGEANT, 1))
        assertEquals(Ranks.MAX, p.addService(CommanderType.SERGEANT, 100_000))
        assertEquals(Ranks.MAX, p.stat(Stat.BEST_RANK))
        assertEquals(1, p.rank(CommanderType.ENGINEER), "each commander has his own")
        for (rank in 2..Ranks.MAX) assertNotNull(RankPerk.at(rank), "rank $rank brings something")
        assertTrue(RankPerk.THIRD.text(CommanderType.GROM).contains(Skill.MINE.title.t))

        val first = Rules(commander = CommanderType.SERGEANT)
        val top = Rules(commander = CommanderType.SERGEANT, rank = Ranks.MAX)
        assertEquals(1f, first.heroDamageMul)
        assertEquals(1.2f, top.heroDamageMul, 0.001f)
        assertEquals(0.8f, top.heroCooldownMul, 0.001f)
        assertEquals(1.1f, top.heroRangeMul, 0.001f)
        assertEquals(1.25f, top.xpMul, 0.001f)
        assertEquals(0, first.commanderLevel)
        assertEquals(1, top.commanderLevel)
        assertEquals(2, Rules(vip = true, commander = CommanderType.SERGEANT, rank = Ranks.MAX).commanderLevel, "VIP and rank add up")
        assertEquals(2, first.heroSkills)
        assertEquals(3, Rules(commander = CommanderType.SERGEANT, rank = Ranks.THIRD_SKILL).heroSkills)
    }

    @Test
    fun theThirdSkillComesAtRankFive() {
        val low = world(CommanderType.SERGEANT, Ranks.THIRD_SKILL - 1)
        nextTo(low, EnemyType.BRUTE, 20f)
        assertFalse(low.skillOpen(2))
        assertFalse(low.useSkill(2))
        val high = world(CommanderType.SERGEANT, Ranks.THIRD_SKILL)
        nextTo(high, EnemyType.BRUTE, 20f)
        assertTrue(high.useSkill(2))
        assertTrue(high.commander!!.skillCooldowns[2] > 0f)
    }

    @Test
    fun suppressionMarksAndSlowsEveryoneAround() {
        val w = world(CommanderType.SERGEANT, Ranks.THIRD_SKILL)
        assertFalse(w.useSkill(2), "no one around, no one to suppress")
        val e = nextTo(w, EnemyType.BRUTE, 20f)
        e.stunTimer = 0f
        assertTrue(w.useSkill(2))
        assertTrue(e.markTimer >= World.SUPPRESS_TIME)
        assertTrue(e.slowTimer > 0f)
    }

    @Test
    fun overchargeSpeedsUpTheSquadsNearby() {
        val w = world(CommanderType.ENGINEER, Ranks.THIRD_SKILL)
        val c = w.commander!!
        var cell = -1
        var best = Float.MAX_VALUE
        for (r in 0 until ROWS) for (col in 0 until COLS) {
            if (!w.canPlace(TowerType.TURRET, col, r)) continue
            val d = hypot(col + 0.5f - c.x, r + 0.5f - c.y)
            if (d < best) {
                best = d
                cell = r * COLS + col
            }
        }
        w.money = 1000
        val squad = assertNotNull(w.build(TowerType.TURRET, cell % COLS, cell / COLS))
        assertTrue(w.useSkill(2))
        run(w, 0.1f)
        assertEquals(World.OVERCHARGE_BONUS, squad.haste)
        run(w, w.skillPower(c, Skill.OVERCHARGE))
        assertEquals(0f, squad.haste, "it wears off")
    }

    @Test
    fun aLandMineWaitsOnTheRoadAndBlowsUnderAWalker() {
        val w = world(CommanderType.GROM, Ranks.THIRD_SKILL)
        val c = w.commander!!
        assertTrue(w.useSkill(2))
        val trap = w.traps.single()
        assertTrue(hypot(trap.x - c.x, trap.y - c.y) <= w.commanderRange(c) * World.TRAP_REACH + 0.01f, "laid within his reach")
        val boss = w.spawnForTest(EnemyType.APC, distNear(w, trap.x, trap.y))
        boss.stunTimer = 1000f
        run(w, 0.2f)
        assertEquals(1, w.traps.size, "it arms first")
        val hp = boss.hp
        run(w, Trap.ARMING)
        assertTrue(w.traps.isEmpty())
        assertEquals(trap.damage * World.TRAP_BOSS, hp - boss.hp, trap.damage * 0.6f, "bosses take it twice")

        // Two at most; a third replaces the oldest.
        val again = world(CommanderType.GROM, Ranks.THIRD_SKILL)
        repeat(3) {
            again.commander!!.skillCooldowns[2] = 0f
            assertTrue(again.useSkill(2))
        }
        assertEquals(World.MAX_TRAPS, again.traps.size)
    }

    @Test
    fun ranksSkillsAndMinesAreSavedWithTheBattle() {
        val w = world(CommanderType.GROM, 6)
        assertTrue(w.useSkill(2))
        w.commander!!.earned = 1234f
        run(w, 0.5f)
        val back = World(Levels.all[0], Rules(progress = 10, commander = CommanderType.GROM, rank = 6))
        back.restore(w.save())
        val c2 = assertNotNull(back.commander)
        assertEquals(w.commander!!.skillCooldowns[2], c2.skillCooldowns[2], 0.001f)
        assertEquals(1234f, c2.earned, 0.01f)
        assertEquals(1, back.traps.size)
        assertEquals(w.traps[0].damage, back.traps[0].damage, 0.01f)

        val s = Session(Mode.CAMPAIGN, Levels.all[0], Difficulty.NORMAL, false, 20_000, 10, Perks.NONE, false, CommanderType.GROM, 6, WorldListener.NONE)
        val loaded = assertNotNull(Session.load(s.save(), WorldListener.NONE))
        assertEquals(6, loaded.rules.rank)
    }

    @Test
    fun aBattleAddsToTheCommandersService() {
        val game = Game(SilentPlatform())
        game.resize(1080, 2400, 2.75f, 80, 60)
        game.profile.tips = false
        game.startCampaign(Levels.all[0], Difficulty.NORMAL, false)
        repeat(20) { game.update(1f / 30f) }
        val w = assertNotNull(game.play.world)
        val c = assertNotNull(w.commander)
        c.earned = 5000f
        w.winForTest()
        repeat(60) { game.update(1f / 30f) }
        val earned = Ranks.earned(c, true)
        assertEquals((5000f / Ranks.XP_PER_SERVICE).toInt() + Ranks.WIN_SERVICE, earned)
        assertEquals(earned, game.profile.service(CommanderType.SERGEANT))
        assertEquals(Ranks.of(earned), game.profile.rank(CommanderType.SERGEANT))
        assertEquals(Ranks.MOST, Ranks.earned(c.also { it.earned = 1e6f }, true), "a long battle gives no more than the cap")
    }
}
