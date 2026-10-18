package com.makc.basedefense.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class Style { PRIMARY, NORMAL, GHOST, DANGER }

/** A white picture in the atlas that the interface paints in any colour. */
internal enum class Icon {
    BACK, GEAR, TROPHY, BOOK, RANKS, CALENDAR, INFINITY, PLAY, PAUSE, CROSS, UNDO, LOCK, STAR, MEDAL,
    SPEED, FLAG, TARGET, SOUND, MUSIC, WARNING, HOME, CHECK, INFO, HEART, SKULL, CROWN,
    ;

    val sprite = "icon." + name.lowercase()
}

/**
 * A tiny immediate-mode toolkit: every frame the screens draw their buttons,
 * sliders and scroll lists again, and touches are matched against what was drawn.
 *
 * It looks like kit in a field headquarters: olive plates with cut corners and
 * rivets, signal-yellow buttons, stencil letters, a contour map behind the menus
 * and a sheet of paper with a stamp for news.
 */
internal class Ui(private val sound: (Sfx) -> Unit) {
    private class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + w && py >= y && py <= y + h
    }

    private class Btn(val id: String, val r: Rect, val clip: Rect?, val enabled: Boolean, val silent: Boolean, val action: () -> Unit) {
        fun contains(px: Float, py: Float): Boolean = r.contains(px, py) && (clip == null || clip.contains(px, py))
    }

    private class Slider(val id: String, val r: Rect, val onChange: (Float) -> Unit)

    private class Scroll(val id: String, val r: Rect, val horizontal: Boolean)

    var w = 0f
    var h = 0f
    var dp = 1f
    var top = 0f
    var bottom = 0f

    private val buttons = ArrayList<Btn>()
    private val sliders = ArrayList<Slider>()
    private val scrolls = ArrayList<Scroll>()
    private val offsets = HashMap<String, Float>()
    private val limits = HashMap<String, Float>()
    private var clip: Rect? = null

    var pressed: String? = null
        private set
    private var slider: String? = null
    private var scroll: Scroll? = null
    private var scrolling = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** True while a touch that started on a control is held. */
    val busy: Boolean get() = pressed != null || slider != null || scrolling

    fun d(v: Float): Float = v * dp

    /** Centre of the control drawn last frame under [id]; tests tap there. */
    fun centre(id: String): Pair<Float, Float>? =
        buttons.lastOrNull { it.id == id }?.let { (it.r.x + it.r.w / 2f) to (it.r.y + it.r.h / 2f) }

    fun ids(): List<String> = buttons.map { it.id } + sliders.map { it.id }

    /** Left, top, width and height of a slider drawn last frame. */
    fun sliderRect(id: String): FloatArray? = sliders.lastOrNull { it.id == id }?.let { floatArrayOf(it.r.x, it.r.y, it.r.w, it.r.h) }

    /** Forget last frame's controls; call before drawing a screen or an overlay on top of it. */
    fun begin() {
        buttons.clear()
        sliders.clear()
        scrolls.clear()
        clip = null
    }

    // --- touch ----------------------------------------------------------------------------

    /** Returns true when the touch landed on a control. */
    fun touch(action: Touch, x: Float, y: Float): Boolean {
        when (action) {
            Touch.DOWN -> {
                downX = x
                downY = y
                lastX = x
                lastY = y
                scrolling = false
                val s = sliders.lastOrNull { it.r.contains(x, y) }
                if (s != null) {
                    slider = s.id
                    s.onChange(((x - s.r.x) / s.r.w).coerceIn(0f, 1f))
                    return true
                }
                scroll = scrolls.lastOrNull { it.r.contains(x, y) }
                val b = buttons.lastOrNull { it.contains(x, y) }
                pressed = b?.id
                return b != null || scroll != null
            }
            Touch.MOVE -> {
                slider?.let { id ->
                    sliders.firstOrNull { it.id == id }?.let { s -> s.onChange(((x - s.r.x) / s.r.w).coerceIn(0f, 1f)) }
                    return true
                }
                val sc = scroll
                if (sc != null) {
                    val moved = if (sc.horizontal) abs(x - downX) else abs(y - downY)
                    if (!scrolling && moved > d(8f)) {
                        scrolling = true
                        pressed = null
                    }
                    if (scrolling) {
                        val delta = if (sc.horizontal) lastX - x else lastY - y
                        offsets[sc.id] = ((offsets[sc.id] ?: 0f) + delta).coerceIn(0f, limits[sc.id] ?: 0f)
                    }
                }
                lastX = x
                lastY = y
                return pressed != null || scrolling
            }
            Touch.UP -> {
                if (slider != null) {
                    slider = null
                    return true
                }
                val wasScrolling = scrolling
                scrolling = false
                scroll = null
                val p = pressed ?: return wasScrolling
                pressed = null
                val b = buttons.lastOrNull { it.contains(x, y) }
                if (b != null && b.id == p) {
                    if (b.enabled) {
                        if (!b.silent) sound(Sfx.CLICK)
                        b.action()
                    } else {
                        sound(Sfx.ERROR)
                    }
                }
                return true
            }
            Touch.CANCEL -> {
                pressed = null
                slider = null
                scroll = null
                scrolling = false
                return false
            }
        }
    }

    // --- scrolling ------------------------------------------------------------------------

    /**
     * Starts a list that scrolls inside (x, y, w, h); returns the offset to subtract
     * from content positions. Call [endScroll] after drawing the content.
     */
    fun beginScroll(g: Gfx, id: String, x: Float, y: Float, sw: Float, sh: Float, content: Float, horizontal: Boolean = false): Float {
        val r = Rect(x, y, sw, sh)
        scrolls += Scroll(id, r, horizontal)
        val limit = max(0f, content - (if (horizontal) sw else sh))
        limits[id] = limit
        val off = (offsets[id] ?: 0f).coerceIn(0f, limit)
        offsets[id] = off
        g.save()
        g.clip(x, y, sw, sh)
        clip = r
        return off
    }

    fun endScroll(g: Gfx, id: String) {
        g.restore()
        clip = null
        val r = scrolls.lastOrNull { it.id == id }?.r ?: return
        val limit = limits[id] ?: 0f
        if (limit <= 0f) return
        val off = offsets[id] ?: 0f
        val horizontal = scrolls.last { it.id == id }.horizontal
        // A thin bar shows there is more.
        if (horizontal) {
            val bw = r.w * r.w / (r.w + limit)
            val bx = r.x + (r.w - bw) * (off / limit)
            chamfer(g, bx, r.y + r.h - d(3.5f), bw, d(3f), d(1f), Colors.alpha(PANEL_HI, 0.9f))
        } else {
            val bh = r.h * r.h / (r.h + limit)
            val by = r.y + (r.h - bh) * (off / limit)
            chamfer(g, r.x + r.w - d(4f), by, d(3f), bh, d(1f), Colors.alpha(PANEL_HI, 0.9f))
        }
    }

    fun resetScroll(id: String) {
        offsets.remove(id)
    }

    fun scrollTo(id: String, value: Float) {
        offsets[id] = value
    }

    // --- text -----------------------------------------------------------------------------

    /** [size], made smaller when [s] would be wider than [maxW]. */
    fun fit(g: Gfx, s: String, size: Float, maxW: Float, font: Font = Font.BOLD): Float {
        if (maxW <= 0f) return size
        val tw = g.textWidth(s, size, font)
        return if (tw > maxW) size * maxW / tw else size
    }

    fun text(g: Gfx, s: String, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT, maxW: Float = 0f, font: Font = Font.BOLD) {
        g.text(s, x, y, fit(g, s, size, maxW, font), color, align, font)
    }

    /** Text whose capitals are centred on [cy]. */
    fun textMid(g: Gfx, s: String, x: Float, cy: Float, size: Float, color: Int, align: Align = Align.CENTER, maxW: Float = 0f, font: Font = Font.BOLD) {
        val sz = fit(g, s, size, maxW, font)
        g.text(s, x, cy + sz * MIDDLE[font.ordinal], sz, color, align, font)
    }

    /** Splits [s] into lines no wider than [maxW]. */
    fun lines(g: Gfx, s: String, size: Float, maxW: Float, font: Font = Font.BODY): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && g.textWidth(next, size, font) > maxW) {
                    out += line
                    line = word
                } else {
                    line = next
                }
            }
            out += line
        }
        return out
    }

    /** Draws wrapped text from the top at [y]; returns its height. */
    fun paragraph(
        g: Gfx,
        s: String,
        x: Float,
        y: Float,
        maxW: Float,
        size: Float,
        color: Int,
        align: Align = Align.LEFT,
        spacing: Float = 1.35f,
        font: Font = Font.BODY,
    ): Float {
        val ls = lines(g, s, size, maxW, font)
        val step = size * spacing
        for ((i, l) in ls.withIndex()) g.text(l, x, y + size + i * step, size, color, align, font)
        return size + (ls.size - 1) * step + size * 0.3f
    }

    /** A small label over a group of controls. */
    fun caption(g: Gfx, s: String, x: Float, y: Float, color: Int = DIM, maxW: Float = 0f) {
        text(g, s.uppercase(), x, y, d(10.5f), color, maxW = maxW)
    }

    // --- shapes ---------------------------------------------------------------------------

    private val px = FloatArray(12)
    private val py = FloatArray(12)

    /** A rectangle with its corners cut [cut] deep, the shape of every plate here. */
    fun chamfer(g: Gfx, x: Float, y: Float, cw: Float, ch: Float, cut: Float, color: Int) {
        if (cw <= 0f || ch <= 0f) return
        val k = min(cut, min(cw, ch) / 2f)
        px[0] = x + k; py[0] = y
        px[1] = x + cw - k; py[1] = y
        px[2] = x + cw; py[2] = y + k
        px[3] = x + cw; py[3] = y + ch - k
        px[4] = x + cw - k; py[4] = y + ch
        px[5] = x + k; py[5] = y + ch
        px[6] = x; py[6] = y + ch - k
        px[7] = x; py[7] = y + k
        g.fillPolygon(px, py, 8, color)
    }

    private val rim: Float get() = max(1f, d(1f))

    /** The edge of a [chamfer] shape, as a line. */
    fun outline(g: Gfx, x: Float, y: Float, cw: Float, ch: Float, cut: Float, color: Int, width: Float) {
        val k = min(cut, min(cw, ch) / 2f)
        px[0] = x + k; py[0] = y
        px[1] = x + cw - k; py[1] = y
        px[2] = x + cw; py[2] = y + k
        px[3] = x + cw; py[3] = y + ch - k
        px[4] = x + cw - k; py[4] = y + ch
        px[5] = x + k; py[5] = y + ch
        px[6] = x; py[6] = y + ch - k
        px[7] = x; py[7] = y + k
        px[8] = px[0]; py[8] = py[0]
        g.polyline(px, py, 9, color, width)
    }

    /** [n] rank chevrons in a row from [x], [size] tall; returns their width. */
    fun chevrons(g: Gfx, x: Float, cy: Float, n: Int, size: Float, color: Int): Float {
        val cw = size * 0.9f
        for (k in 0 until n) {
            val l = x + k * (cw + size * 0.18f)
            val t = cy - size * 0.45f
            px[0] = l; py[0] = t + size * 0.5f
            px[1] = l + cw / 2f; py[1] = t
            px[2] = l + cw; py[2] = t + size * 0.5f
            px[3] = l + cw; py[3] = t + size * 0.9f
            px[4] = l + cw / 2f; py[4] = t + size * 0.42f
            px[5] = l; py[5] = t + size * 0.9f
            g.fillPolygon(px, py, 6, color)
        }
        return if (n <= 0) 0f else n * cw + (n - 1) * size * 0.18f
    }


    /** A raised plate with a dark rim and a lit top edge: rows, cards, tiles. */
    fun plate(g: Gfx, x: Float, y: Float, pw: Float, ph: Float, face: Int = PANEL_LIGHT, edge: Int = EDGE, cut: Float = d(6f)) {
        val o = if (edge == EDGE) rim else d(2f)
        chamfer(g, x, y, pw, ph, cut, edge)
        chamfer(g, x + o, y + o, pw - 2f * o, ph - 2f * o, cut, Colors.darker(face, 0.35f))
        chamfer(g, x + o, y + o, pw - 2f * o, ph - 2f * o - d(1.5f), cut, Colors.lighter(face, 0.13f))
        chamfer(g, x + o, y + o + d(1.5f), pw - 2f * o, ph - 2f * o - d(3f), cut, face)
    }

    /** A sunken slot: the inside of switches, tracks and tags. */
    fun well(g: Gfx, x: Float, y: Float, ww: Float, wh: Float, cut: Float = d(4f), face: Int = WELL) {
        chamfer(g, x, y, ww, wh, cut, EDGE)
        chamfer(g, x + rim, y + d(2f), ww - 2f * rim, wh - d(2f) - rim, cut, face)
    }

    fun rivet(g: Gfx, cx: Float, cy: Float) {
        g.fillCircle(cx, cy + d(0.6f), d(2.4f), EDGE)
        g.fillCircle(cx, cy, d(2f), PANEL_HI)
        g.fillCircle(cx - d(0.6f), cy - d(0.6f), d(0.9f), Colors.lighter(PANEL_HI, 0.4f))
    }

    /** Yellow and black warning stripes. */
    fun hazard(g: Gfx, x: Float, y: Float, hw: Float, hh: Float, color: Int = ACCENT) {
        g.save()
        g.clip(x, y, hw, hh)
        g.fillRect(x, y, hw, hh, color)
        val stripe = d(7f)
        var sx = x - hh
        while (sx < x + hw) {
            px[0] = sx; py[0] = y + hh
            px[1] = sx + hh; py[1] = y
            px[2] = sx + hh + stripe; py[2] = y
            px[3] = sx + stripe; py[3] = y + hh
            g.fillPolygon(px, py, 4, STRIPE)
            sx += stripe * 2f
        }
        g.restore()
    }

    /**
     * A dialog: a riveted olive plate, with a dark title band and a strip of warning
     * stripes under it when there is a [title]. Returns where its content starts.
     */
    fun panel(g: Gfx, x: Float, y: Float, pw: Float, ph: Float, edge: Int = EDGE, title: String? = null, band: Int = BAND, ink: Int = TEXT): Float {
        val cut = d(12f)
        chamfer(g, x, y + d(6f), pw, ph, cut, 0x66000000)
        chamfer(g, x, y, pw, ph, cut, edge)
        val o = if (edge == EDGE) rim else d(2f)
        chamfer(g, x + o, y + o, pw - 2f * o, ph - 2f * o, cut, PANEL_DARK)
        chamfer(g, x + o, y + o, pw - 2f * o, ph - 2f * o - d(2f), cut, PANEL_HI)
        chamfer(g, x + o, y + o + d(2f), pw - 2f * o, ph - 2f * o - d(4f), cut, PANEL)
        var contentTop = y + d(14f)
        if (title != null) {
            val bh = d(50f)
            g.save()
            g.clip(x, y, pw, bh)
            chamfer(g, x + o, y + o, pw - 2f * o, ph - 2f * o, cut, band)
            g.restore()
            hazard(g, x + o, y + bh, pw - 2f * o, d(5f))
            textMid(g, title, x + pw / 2f, y + bh / 2f + d(1f), d(21f), ink, maxW = pw - d(124f), font = Font.DISPLAY)
            contentTop = y + bh + d(5f)
        }
        val r = d(10f)
        rivet(g, x + r, y + r)
        rivet(g, x + pw - r, y + r)
        rivet(g, x + r, y + ph - r)
        rivet(g, x + pw - r, y + ph - r)
        return contentTop
    }

    /** A sheet of paper with a clip: the field reports that bring news. */
    fun paper(g: Gfx, x: Float, y: Float, pw: Float, ph: Float) {
        g.fillRect(x + d(3f), y + d(6f), pw, ph, 0x66000000)
        g.fillRect(x, y, pw, ph, PAPER_EDGE)
        g.fillRect(x + rim, y + rim, pw - 2f * rim, ph - 2f * rim, PAPER)
        // Faint rules, like a form.
        var ly = y + d(30f)
        while (ly < y + ph - d(10f)) {
            g.fillRect(x + d(12f), ly, pw - d(24f), max(1f, d(0.6f)), Colors.alpha(PAPER_EDGE, 0.35f))
            ly += d(22f)
        }
        // A paper clip over the top edge.
        val cx = x + d(26f)
        g.strokeRoundRect(cx - d(6f), y - d(12f), d(12f), d(34f), d(6f), STEEL_DARK, d(2.2f))
        g.strokeRoundRect(cx - d(3f), y - d(6f), d(6f), d(22f), d(3f), STEEL, d(2f))
    }

    /** A rubber stamp, turned a little, as if pressed by hand. */
    fun stamp(g: Gfx, s: String, cx: Float, cy: Float, size: Float, color: Int = STAMP, degrees: Float = -7f) {
        g.save()
        g.translate(cx, cy)
        g.rotate(degrees)
        val ink = Colors.alpha(color, 0.88f)
        val bw = g.textWidth(s, size, Font.DISPLAY) + size * 1.2f
        val bh = size * 1.8f
        g.strokeRoundRect(-bw / 2f, -bh / 2f, bw, bh, size * 0.18f, ink, size * 0.13f)
        g.strokeRoundRect(-bw / 2f + size * 0.22f, -bh / 2f + size * 0.22f, bw - size * 0.44f, bh - size * 0.44f, size * 0.1f, ink, size * 0.05f)
        g.text(s, 0f, size * MIDDLE[Font.DISPLAY.ordinal], size, ink, Align.CENTER, Font.DISPLAY)
        g.restore()
    }

    /**
     * Small sunken tags in a row, e.g. stats; returns their height. They shrink to
     * fit [maxW], or with [wrap] go on in more rows.
     */
    fun chips(
        g: Gfx,
        items: List<String>,
        x: Float,
        y: Float,
        maxW: Float,
        color: Int = TEXT,
        size: Float = d(11f),
        face: Int = WELL,
        wrap: Boolean = false,
        centre: Boolean = false,
    ): Float {
        if (items.isEmpty()) return 0f
        val pad = d(6f)
        val gap = d(4f)
        val fixed = items.size * 2f * pad + gap * (items.size - 1)
        val words = items.fold(0f) { acc, s -> acc + g.textWidth(s, size, Font.BOLD) }
        val sz = if (!wrap && fixed + words > maxW && words > 0f) size * max(0.62f, (maxW - fixed) / words) else size
        val ch = sz + d(8f)
        var cx = x
        if (centre && !wrap) {
            val total = items.size * 2f * pad + gap * (items.size - 1) + items.fold(0f) { acc, s -> acc + g.textWidth(s, sz, Font.BOLD) }
            cx = x + max(0f, (maxW - total) / 2f)
        }
        var cy = y
        for (s in items) {
            val cw = g.textWidth(s, sz, Font.BOLD) + 2f * pad
            if (cx + cw > x + maxW + 1f) {
                if (!wrap || cx == x) break
                cx = x
                cy += ch + gap
            }
            chamfer(g, cx, cy, cw, ch, d(3f), face)
            g.text(s, cx + pad, cy + ch / 2f + sz * MIDDLE[Font.BOLD.ordinal], sz, color, Align.LEFT, Font.BOLD)
            cx += cw + gap
        }
        return cy - y + ch
    }

    /** A small plate with a word or number; [x] is its left edge, or its right one when [right]. Returns its width. */
    fun tag(
        g: Gfx,
        s: String,
        x: Float,
        cy: Float,
        size: Float,
        face: Int = ACCENT,
        ink: Int = DARK_TEXT,
        right: Boolean = false,
        font: Font = Font.DISPLAY,
    ): Float {
        val tw = g.textWidth(s, size, font) + size * 0.95f
        val th = size * 1.6f
        val left = if (right) x - tw else x
        chamfer(g, left, cy - th / 2f, tw, th, size * 0.32f, EDGE)
        chamfer(g, left + rim, cy - th / 2f + rim, tw - 2f * rim, th - 2f * rim, size * 0.3f, face)
        g.text(s, left + tw / 2f, cy + size * MIDDLE[font.ordinal], size, ink, Align.CENTER, font)
        return tw
    }

    /** Stencil letters on a yellow plate turned a little, like the sign over a bunker door. */
    fun tape(g: Gfx, s: String, cx: Float, cy: Float, size: Float, degrees: Float) {
        val pw = g.textWidth(s, size, Font.DISPLAY) + size * 1.1f
        val ph = size * 1.24f
        val cut = size * 0.16f
        g.save()
        g.translate(cx, cy)
        g.rotate(degrees)
        chamfer(g, -pw / 2f + d(3f), -ph / 2f + d(5f), pw, ph, cut, 0x80000000.toInt())
        chamfer(g, -pw / 2f, -ph / 2f, pw, ph, cut, EDGE)
        chamfer(g, -pw / 2f + d(2f), -ph / 2f + d(2f), pw - d(4f), ph - d(4f), cut, ACCENT_LIT)
        chamfer(g, -pw / 2f + d(2f), -ph / 2f + d(4.5f), pw - d(4f), ph - d(6.5f), cut, ACCENT)
        for (k in 0..1) {
            val bx = (if (k == 0) -1f else 1f) * (pw / 2f - size * 0.24f)
            g.fillCircle(bx, d(1f), size * 0.075f, ACCENT_DARK)
            g.fillCircle(bx - size * 0.02f, 0f, size * 0.05f, ACCENT_LIT)
        }
        g.text(s, 0f, size * MIDDLE[Font.DISPLAY.ordinal] + d(1f), size, DARK_TEXT, Align.CENTER, Font.DISPLAY)
        g.restore()
    }

    /** Behind every menu: a contour map of the hills around the base, drawn once. */
    fun backdrop(g: Gfx) {
        g.cached("backdrop-${w.toInt()}x${h.toInt()}", w, h, 1f) { contourMap(it) }
    }

    private fun contourMap(g: Gfx) {
        g.fillRect(0f, 0f, w, h, BG_DEEP)
        g.glow(w * 0.5f, h * 0.45f, max(w, h) * 0.75f, Colors.alpha(BG_LIT, 0.9f))
        // Map squares, marked only at their corners.
        val step = d(64f)
        var gy = step * 0.5f
        while (gy < h) {
            var gx = step * 0.5f
            while (gx < w) {
                g.line(gx - d(4f), gy, gx + d(4f), gy, Colors.alpha(TEXT, 0.08f), d(1f))
                g.line(gx, gy - d(4f), gx, gy + d(4f), Colors.alpha(TEXT, 0.08f), d(1f))
                gx += step
            }
            gy += step
        }
        // Height lines by marching squares over a gentle field of hills.
        val cs = d(7f)
        val nx = (w / cs).toInt() + 2
        val ny = (h / cs).toInt() + 2
        val f = FloatArray(nx * ny)
        for (j in 0 until ny) for (i in 0 until nx) f[j * nx + i] = hills(i * cs / dp, j * cs / dp)
        for (level in 1..16) {
            val v = level * 0.085f
            val strong = level % 4 == 0
            val color = Colors.alpha(CONTOUR, if (strong) 0.55f else 0.32f)
            val width = if (strong) d(1.5f) else d(1f)
            for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
                val a = f[j * nx + i]
                val b = f[j * nx + i + 1]
                val c = f[(j + 1) * nx + i + 1]
                val e = f[(j + 1) * nx + i]
                val case = (if (a > v) 8 else 0) or (if (b > v) 4 else 0) or (if (c > v) 2 else 0) or (if (e > v) 1 else 0)
                if (case == 0 || case == 15) continue
                val x0 = i * cs
                val y0 = j * cs
                // Where the line crosses each side: top, right, bottom, left.
                val tx = x0 + cs * cross(a, b, v)
                val ry = y0 + cs * cross(b, c, v)
                val bx = x0 + cs * cross(e, c, v)
                val ly = y0 + cs * cross(a, e, v)
                val top = (a > v) != (b > v)
                val right = (b > v) != (c > v)
                val bottom = (e > v) != (c > v)
                val left = (a > v) != (e > v)
                if (top && right && bottom && left) {
                    val centre = (a + b + c + e) / 4f > v
                    if (centre == (a > v)) {
                        g.line(tx, y0, x0 + cs, ry, color, width)
                        g.line(bx, y0 + cs, x0, ly, color, width)
                    } else {
                        g.line(x0, ly, tx, y0, color, width)
                        g.line(x0 + cs, ry, bx, y0 + cs, color, width)
                    }
                    continue
                }
                var n = 0
                if (top) { px[n] = tx; py[n] = y0; n++ }
                if (right) { px[n] = x0 + cs; py[n] = ry; n++ }
                if (bottom) { px[n] = bx; py[n] = y0 + cs; n++ }
                if (left) { px[n] = x0; py[n] = ly; n++ }
                if (n == 2) g.line(px[0], py[0], px[1], py[1], color, width)
            }
        }
    }

    private fun cross(p: Float, q: Float, v: Float): Float = if (q == p) 0.5f else ((v - p) / (q - p)).coerceIn(0f, 1f)

    /** Height of the made-up land at (x, y) in dp. */
    private fun hills(x: Float, y: Float): Float {
        val wd = w / dp
        val hd = h / dp
        var s = 0.1f * sin(x / 61f + y / 97f) * cos(y / 73f - x / 131f)
        var k = 0
        while (k < HILLS.size) {
            val dx = x - HILLS[k] * wd
            val dy = y - HILLS[k + 1] * hd
            val r = HILLS[k + 2]
            s += HILLS[k + 3] * exp(-(dx * dx + dy * dy) / (2f * r * r))
            k += 4
        }
        return s
    }

    // --- controls -------------------------------------------------------------------------

    /** An invisible pressable area. */
    fun area(id: String, x: Float, y: Float, aw: Float, ah: Float, enabled: Boolean = true, silent: Boolean = false, action: () -> Unit) {
        buttons += Btn(id, Rect(x, y, aw, ah), clip, enabled, silent, action)
    }

    fun down(id: String): Boolean = pressed == id

    /**
     * A chunky plate that sinks when pressed. [sub] is a smaller second line;
     * the label is in stencil letters, [size] in dp.
     */
    fun button(
        g: Gfx,
        id: String,
        x: Float,
        y: Float,
        bw: Float,
        bh: Float,
        label: String,
        style: Style = Style.NORMAL,
        enabled: Boolean = true,
        size: Float = 15f,
        icon: Icon? = null,
        sub: String? = null,
        action: () -> Unit,
    ) {
        area(id, x, y, bw, bh, enabled, action = action)
        val isDown = pressed == id && enabled
        val ghost = style == Style.GHOST && enabled
        val cut = min(d(7f), bh / 4f)
        val o = rim
        val face: Int
        val lit: Int
        val side: Int
        val ink: Int
        when {
            !enabled -> {
                face = OFF_FACE
                lit = Colors.lighter(OFF_FACE, 0.06f)
                side = PANEL_DARK
                ink = FADED
            }
            style == Style.PRIMARY -> {
                face = if (isDown) ACCENT_DOWN else ACCENT
                lit = ACCENT_LIT
                side = ACCENT_DARK
                ink = DARK_TEXT
            }
            style == Style.DANGER -> {
                face = if (isDown) DANGER_DOWN else DANGER
                lit = DANGER_LIT
                side = DANGER_DARK
                ink = TEXT
            }
            style == Style.NORMAL -> {
                face = if (isDown) OLIVE_DOWN else OLIVE
                lit = OLIVE_LIT
                side = OLIVE_DARK
                ink = TEXT
            }
            else -> {
                face = if (isDown) PANEL_LIGHT else WELL
                lit = EDGE
                side = 0
                ink = TEXT
            }
        }
        val depth = if (ghost) 0f else d(3f)
        val sink = if (isDown) min(d(2f), depth) else 0f
        if (depth > 0f) {
            chamfer(g, x, y + depth, bw, bh, cut, EDGE)
            chamfer(g, x + o, y + depth, bw - 2f * o, bh - o, cut, side)
        }
        chamfer(g, x, y + sink, bw, bh, cut, if (ghost) PANEL_HI else EDGE)
        chamfer(g, x + o, y + sink + o, bw - 2f * o, bh - 2f * o, cut, lit)
        chamfer(g, x + o, y + sink + o + d(1.5f), bw - 2f * o, bh - 2f * o - d(1.5f), cut, face)

        val cy = y + bh / 2f + sink
        val sz = d(size)
        val labelY = if (sub != null) cy - bh * 0.13f else cy
        if (icon != null && label.isEmpty()) {
            this.icon(g, icon, x + bw / 2f, cy, min(bw, bh) * 0.48f, ink)
        } else if (icon != null) {
            val s = min(bh * 0.44f, d(21f))
            val room = bw - s - d(26f)
            val tw = min(g.textWidth(label, sz, Font.DISPLAY), room)
            val left = x + (bw - tw - s - d(9f)) / 2f
            this.icon(g, icon, left + s / 2f, labelY, s, ink)
            textMid(g, label, left + s + d(9f), labelY, sz, ink, Align.LEFT, maxW = room, font = Font.DISPLAY)
        } else {
            textMid(g, label, x + bw / 2f, labelY, sz, ink, maxW = bw - d(16f), font = Font.DISPLAY)
        }
        if (sub != null) {
            val subInk = when {
                !enabled -> FADED
                style == Style.PRIMARY -> Colors.alpha(DARK_TEXT, 0.75f)
                else -> DIM
            }
            textMid(g, sub, x + bw / 2f, cy + bh * 0.23f, sz * 0.66f, subInk, maxW = bw - d(16f))
        }
    }

    /** A lever in a slot: yellow behind it when on. */
    fun toggle(
        g: Gfx,
        id: String,
        x: Float,
        y: Float,
        tw: Float,
        th: Float,
        label: String,
        value: Boolean,
        enabled: Boolean = true,
        icon: Icon? = null,
        onChange: (Boolean) -> Unit,
    ) {
        area(id, x, y, tw, th, enabled) { onChange(!value) }
        var lx = x + d(4f)
        if (icon != null) {
            this.icon(g, icon, lx + d(9f), y + th / 2f, d(17f), DIM)
            lx += d(26f)
        }
        if (label.isNotEmpty()) textMid(g, label, lx, y + th / 2f, d(15f), if (enabled) TEXT else FADED, Align.LEFT, maxW = x + tw - d(72f) - lx)
        val sw = d(54f)
        val sh = d(28f)
        val sx = x + tw - sw - d(4f)
        val sy = y + (th - sh) / 2f
        val on = value && enabled
        well(g, sx, sy, sw, sh, d(5f), if (on) ACCENT_DARK else WELL)
        if (on) chamfer(g, sx + d(3f), sy + d(4f), sw - d(6f), sh - d(7f), d(3f), ACCENT)
        val kw = d(24f)
        val kh = sh - d(4f)
        val shift = if (pressed == id) d(2f) * (if (value) -1f else 1f) else 0f
        val kx = (if (value) sx + sw - kw - d(2f) else sx + d(2f)) + shift
        val ky = sy + d(1f)
        chamfer(g, kx, ky, kw, kh, d(4f), EDGE)
        chamfer(g, kx + rim, ky + rim, kw - 2f * rim, kh - 2f * rim, d(4f), if (on) TEXT else OLIVE_LIT)
        chamfer(g, kx + rim, ky + rim + d(1.5f), kw - 2f * rim, kh - 2f * rim - d(3f), d(4f), if (on) PAPER else OLIVE)
        for (k in -1..1) {
            val gx = kx + kw / 2f + k * d(4.5f)
            g.fillRect(gx - d(0.7f), ky + kh * 0.3f, d(1.4f), kh * 0.4f, if (on) PAPER_EDGE else OLIVE_DARK)
        }
    }

    /** A 0-100 slider with a scale under its slot; [onChange] gets the new value. */
    fun slider(g: Gfx, id: String, x: Float, y: Float, sw: Float, sh: Float, label: String, value: Int, icon: Icon? = null, onChange: (Int) -> Unit) {
        var lx = x + d(4f)
        if (icon != null) {
            this.icon(g, icon, lx + d(9f), y + d(12f), d(17f), DIM)
            lx += d(26f)
        }
        textMid(g, label, lx, y + d(12f), d(15f), TEXT, Align.LEFT, maxW = sw * 0.7f)
        textMid(g, "$value%", x + sw - d(4f), y + d(12f), d(14f), ACCENT, Align.RIGHT, font = Font.DISPLAY)
        val ty = y + sh - d(18f)
        val left = x + d(12f)
        val width = sw - d(24f)
        sliders += Slider(id, Rect(x, y + d(18f), sw, sh - d(18f))) { f -> onChange(Math.round(((f * sw - d(12f)) / width).coerceIn(0f, 1f) * 100f)) }
        for (k in 0..10) {
            val tx = left + width * k / 10f
            g.fillRect(tx - d(0.6f), ty + d(8f), d(1.2f), if (k % 5 == 0) d(6f) else d(3.5f), PANEL_HI)
        }
        well(g, left - d(3f), ty - d(5f), width + d(6f), d(11f), d(3f))
        if (value > 0) g.fillRect(left, ty - d(2f), width * value / 100f, d(5f), ACCENT)
        val kx = left + width * value / 100f
        val kw = d(16f)
        val kh = d(28f)
        val held = slider == id
        chamfer(g, kx - kw / 2f, ty - kh / 2f, kw, kh, d(4f), EDGE)
        chamfer(g, kx - kw / 2f + rim, ty - kh / 2f + rim, kw - 2f * rim, kh - 2f * rim, d(4f), TEXT)
        chamfer(g, kx - kw / 2f + rim, ty - kh / 2f + rim + d(1.5f), kw - 2f * rim, kh - 2f * rim - d(3f), d(4f), if (held) ACCENT else PAPER)
        for (k in -1..1) g.fillRect(kx - kw * 0.28f, ty + k * d(4.5f) - d(0.7f), kw * 0.56f, d(1.4f), PAPER_EDGE)
    }

    /** A row of options where one is chosen: the chosen one is a yellow plate. */
    fun segmented(
        g: Gfx,
        id: String,
        x: Float,
        y: Float,
        sw: Float,
        sh: Float,
        options: List<String>,
        selected: Int,
        enabled: (Int) -> Boolean = { true },
        size: Float = 14f,
        onSelect: (Int) -> Unit,
    ) {
        val gap = d(5f)
        val each = (sw - gap * (options.size - 1)) / options.size
        for ((i, o) in options.withIndex()) {
            val bx = x + i * (each + gap)
            val on = i == selected
            val ok = enabled(i)
            area("$id$i", bx, y, each, sh, ok) { onSelect(i) }
            val isDown = pressed == "$id$i"
            val cut = d(5f)
            if (on) {
                chamfer(g, bx, y, each, sh, cut, EDGE)
                chamfer(g, bx + rim, y + rim, each - 2f * rim, sh - 2f * rim, cut, ACCENT_LIT)
                chamfer(g, bx + rim, y + rim + d(1.5f), each - 2f * rim, sh - 2f * rim - d(1.5f), cut, ACCENT)
            } else {
                well(g, bx, y, each, sh, cut, if (isDown) PANEL_LIGHT else WELL)
            }
            val ink = when {
                !ok -> FADED
                on -> DARK_TEXT
                else -> TEXT
            }
            val lock = if (ok) 0f else d(16f)
            val room = each - d(12f) - lock
            val sz = fit(g, o, d(size), room, Font.DISPLAY)
            val tw = g.textWidth(o, sz, Font.DISPLAY)
            val tx = bx + (each - tw - lock) / 2f + lock
            if (!ok) icon(g, Icon.LOCK, tx - d(9f), y + sh / 2f, d(12f), FADED)
            g.text(o, tx, y + sh / 2f + sz * MIDDLE[Font.DISPLAY.ordinal], sz, ink, Align.LEFT, Font.DISPLAY)
        }
    }

    /** The bar across the top of a menu screen; returns where the screen's content starts. */
    fun header(g: Gfx, title: String, onBack: () -> Unit): Float {
        val bandH = top + d(62f)
        g.fillRect(0f, 0f, w, bandH, Colors.alpha(BAND, 0.94f))
        g.fillRect(0f, bandH, w, d(2f), EDGE)
        g.fillRect(0f, bandH - d(1f), w, d(1f), PANEL_HI)
        val y = top + d(10f)
        button(g, "back", d(12f), y, d(46f), d(40f), "", icon = Icon.BACK, action = onBack)
        // Room is left on the right for a counter or a date.
        textMid(g, title.uppercase(), w / 2f, y + d(20f), d(22f), TEXT, maxW = w - d(200f), font = Font.DISPLAY)
        return bandH + d(12f)
    }

    // --- icons ----------------------------------------------------------------------------

    private val iconSprites: Array<Sprite?> by lazy { Array(Icon.entries.size) { Sprites[Icon.entries[it].sprite] } }
    private val coinSprite: Sprite? by lazy { Sprites["coin"] }

    /** [icon] about [s] across, painted in [color]. */
    fun icon(g: Gfx, icon: Icon, cx: Float, cy: Float, s: Float, color: Int) {
        val sp = if (g.sprites) iconSprites[icon.ordinal] else null
        if (sp != null) {
            g.sprite(sp, cx, cy, s * ICON_SCALE, s * ICON_SCALE, tint = color)
            return
        }
        when (icon) {
            Icon.BACK -> chevron(g, cx, cy, s, color)
            Icon.GEAR -> gear(g, cx, cy, s, color)
            Icon.TROPHY -> trophy(g, cx, cy, s, color)
            Icon.BOOK -> book(g, cx, cy, s, color)
            Icon.RANKS -> upgradeArrow(g, cx, cy, s, color)
            Icon.CALENDAR -> calendar(g, cx, cy, s, color)
            Icon.INFINITY -> infinity(g, cx, cy, s, color)
            Icon.PLAY, Icon.SPEED -> play(g, cx, cy, s, color)
            Icon.PAUSE -> pauseIcon(g, cx, cy, s, color)
            Icon.CROSS -> cross(g, cx, cy, s, color)
            Icon.UNDO -> undoIcon(g, cx, cy, s, color)
            Icon.LOCK -> padlock(g, cx, cy, s, color)
            Icon.STAR -> starShape(g, cx, cy, s * 0.5f, color)
            Icon.MEDAL -> medalShape(g, cx, cy, s, color)
            Icon.FLAG -> {
                g.line(cx - s * 0.3f, cy - s * 0.42f, cx - s * 0.3f, cy + s * 0.42f, color, s * 0.1f)
                px[0] = cx - s * 0.3f; py[0] = cy - s * 0.42f
                px[1] = cx + s * 0.38f; py[1] = cy - s * 0.2f
                px[2] = cx - s * 0.3f; py[2] = cy + s * 0.05f
                g.fillPolygon(px, py, 3, color)
            }
            Icon.TARGET -> {
                g.strokeCircle(cx, cy, s * 0.36f, color, s * 0.12f)
                g.fillCircle(cx, cy, s * 0.12f, color)
            }
            Icon.SOUND, Icon.MUSIC -> {
                g.fillRect(cx - s * 0.36f, cy - s * 0.14f, s * 0.2f, s * 0.28f, color)
                px[0] = cx - s * 0.2f; py[0] = cy - s * 0.14f
                px[1] = cx + s * 0.12f; py[1] = cy - s * 0.4f
                px[2] = cx + s * 0.12f; py[2] = cy + s * 0.4f
                px[3] = cx - s * 0.2f; py[3] = cy + s * 0.14f
                g.fillPolygon(px, py, 4, color)
            }
            Icon.WARNING -> {
                px[0] = cx; py[0] = cy - s * 0.42f
                px[1] = cx + s * 0.46f; py[1] = cy + s * 0.38f
                px[2] = cx - s * 0.46f; py[2] = cy + s * 0.38f
                g.fillPolygon(px, py, 3, color)
            }
            Icon.HOME -> {
                px[0] = cx; py[0] = cy - s * 0.44f
                px[1] = cx + s * 0.44f; py[1] = cy
                px[2] = cx - s * 0.44f; py[2] = cy
                g.fillPolygon(px, py, 3, color)
                g.fillRect(cx - s * 0.3f, cy - s * 0.02f, s * 0.6f, s * 0.4f, color)
            }
            Icon.CHECK -> {
                g.line(cx - s * 0.34f, cy, cx - s * 0.08f, cy + s * 0.28f, color, s * 0.16f)
                g.line(cx - s * 0.08f, cy + s * 0.28f, cx + s * 0.36f, cy - s * 0.26f, color, s * 0.16f)
            }
            Icon.INFO -> {
                g.fillCircle(cx, cy - s * 0.3f, s * 0.09f, color)
                g.fillRect(cx - s * 0.08f, cy - s * 0.12f, s * 0.16f, s * 0.48f, color)
            }
            Icon.HEART -> heartShape(g, cx, cy, s, color)
            Icon.SKULL -> skullShape(g, cx, cy, s, color)
            Icon.CROWN -> {
                px[0] = cx - s * 0.45f; py[0] = cy + s * 0.3f
                px[1] = cx - s * 0.45f; py[1] = cy - s * 0.3f
                px[2] = cx - s * 0.2f; py[2] = cy
                px[3] = cx; py[3] = cy - s * 0.42f
                px[4] = cx + s * 0.2f; py[4] = cy
                px[5] = cx + s * 0.45f; py[5] = cy - s * 0.3f
                px[6] = cx + s * 0.45f; py[6] = cy + s * 0.3f
                g.fillPolygon(px, py, 7, color)
            }
        }
    }

    /** A star [r] in radius: gold when earned, a dark slot when not. */
    fun star(g: Gfx, cx: Float, cy: Float, r: Float, filled: Boolean, color: Int = GOLD) =
        icon(g, Icon.STAR, cx, cy, r * 2.15f, if (filled) color else EMPTY)

    fun heart(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) = icon(g, Icon.HEART, cx, cy, s, color)

    fun coin(g: Gfx, cx: Float, cy: Float, s: Float) {
        val sp = if (g.sprites) coinSprite else null
        if (sp != null) {
            val k = s / max(sp.w, sp.h)
            g.sprite(sp, cx, cy, sp.w * k, sp.h * k)
            return
        }
        g.fillCircle(cx, cy + s * 0.06f, s * 0.5f, 0xFF9C7A16.toInt())
        g.fillCircle(cx, cy, s * 0.5f, GOLD)
        g.strokeCircle(cx, cy, s * 0.34f, 0xFFC99A1E.toInt(), s * 0.08f)
    }

    fun lock(g: Gfx, cx: Float, cy: Float, s: Float, color: Int = DIM) = icon(g, Icon.LOCK, cx, cy, s, color)

    /** The mark of a hard win. */
    fun skull(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) = icon(g, Icon.SKULL, cx, cy, s, color)

    /** The mark of a won challenge. */
    fun medal(g: Gfx, cx: Float, cy: Float, s: Float, filled: Boolean) = icon(g, Icon.MEDAL, cx, cy, s, if (filled) GOLD else EMPTY)

    /** A segmented gauge, filled [f] of the way. */
    fun bar(g: Gfx, x: Float, y: Float, bw: Float, bh: Float, f: Float, color: Int) {
        well(g, x, y, bw, bh, bh / 3f)
        val k = f.coerceIn(0f, 1f)
        if (k > 0f) chamfer(g, x + rim, y + rim + d(1f), max(bh, (bw - 2f * rim) * k), bh - 2f * rim - d(1f), bh / 3f, color)
    }

    // The same pictures drawn with shapes, for when there is no atlas.

    private fun starShape(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        for (k in 0 until 10) {
            val a = -PI / 2 + k * PI / 5
            val rr = if (k % 2 == 0) r else r * 0.45f
            px[k] = cx + (cos(a) * rr).toFloat()
            py[k] = cy + (sin(a) * rr).toFloat()
        }
        g.fillPolygon(px, py, 10, color)
    }

    private fun heartShape(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillCircle(cx - s * 0.27f, cy - s * 0.12f, s * 0.3f, color)
        g.fillCircle(cx + s * 0.27f, cy - s * 0.12f, s * 0.3f, color)
        px[0] = cx - s * 0.55f; py[0] = cy - s * 0.02f
        px[1] = cx + s * 0.55f; py[1] = cy - s * 0.02f
        px[2] = cx; py[2] = cy + s * 0.55f
        g.fillPolygon(px, py, 3, color)
    }

    private fun padlock(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.strokeCircle(cx, cy - s * 0.18f, s * 0.24f, color, s * 0.1f)
        g.fillRoundRect(cx - s * 0.36f, cy - s * 0.12f, s * 0.72f, s * 0.56f, s * 0.08f, color)
    }

    private fun skullShape(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillCircle(cx, cy - s * 0.08f, s * 0.42f, color)
        g.fillRoundRect(cx - s * 0.26f, cy + s * 0.1f, s * 0.52f, s * 0.3f, s * 0.08f, color)
        g.fillCircle(cx - s * 0.16f, cy - s * 0.06f, s * 0.11f, EDGE)
        g.fillCircle(cx + s * 0.16f, cy - s * 0.06f, s * 0.11f, EDGE)
    }

    private fun medalShape(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        px[0] = cx - s * 0.32f; py[0] = cy - s * 0.5f
        px[1] = cx - s * 0.08f; py[1] = cy - s * 0.5f
        px[2] = cx + s * 0.05f; py[2] = cy
        px[3] = cx - s * 0.18f; py[3] = cy
        g.fillPolygon(px, py, 4, color)
        px[0] = cx + s * 0.32f; py[0] = cy - s * 0.5f
        px[1] = cx + s * 0.08f; py[1] = cy - s * 0.5f
        px[2] = cx - s * 0.05f; py[2] = cy
        px[3] = cx + s * 0.18f; py[3] = cy
        g.fillPolygon(px, py, 4, color)
        g.fillCircle(cx, cy + s * 0.15f, s * 0.32f, color)
    }

    private fun chevron(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.line(cx + s * 0.18f, cy - s * 0.4f, cx - s * 0.22f, cy, color, s * 0.18f)
        g.line(cx - s * 0.22f, cy, cx + s * 0.18f, cy + s * 0.4f, color, s * 0.18f)
    }

    private fun gear(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        for (k in 0 until 8) {
            val a = k * PI.toFloat() / 4f
            g.line(cx + cos(a) * s * 0.25f, cy + sin(a) * s * 0.25f, cx + cos(a) * s * 0.5f, cy + sin(a) * s * 0.5f, color, s * 0.2f)
        }
        g.fillCircle(cx, cy, s * 0.36f, color)
        g.fillCircle(cx, cy, s * 0.15f, EDGE)
    }

    private fun trophy(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.32f, cy - s * 0.45f, s * 0.64f, s * 0.5f, s * 0.2f, color)
        g.strokeCircle(cx - s * 0.32f, cy - s * 0.25f, s * 0.14f, color, s * 0.07f)
        g.strokeCircle(cx + s * 0.32f, cy - s * 0.25f, s * 0.14f, color, s * 0.07f)
        g.fillRect(cx - s * 0.06f, cy, s * 0.12f, s * 0.25f, color)
        g.fillRoundRect(cx - s * 0.26f, cy + s * 0.25f, s * 0.52f, s * 0.15f, s * 0.05f, color)
    }

    private fun book(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.42f, cy - s * 0.38f, s * 0.4f, s * 0.76f, s * 0.06f, color)
        g.fillRoundRect(cx + s * 0.02f, cy - s * 0.38f, s * 0.4f, s * 0.76f, s * 0.06f, color)
    }

    private fun upgradeArrow(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        px[0] = cx; py[0] = cy - s * 0.5f
        px[1] = cx + s * 0.42f; py[1] = cy
        px[2] = cx + s * 0.16f; py[2] = cy
        px[3] = cx + s * 0.16f; py[3] = cy + s * 0.45f
        px[4] = cx - s * 0.16f; py[4] = cy + s * 0.45f
        px[5] = cx - s * 0.16f; py[5] = cy
        px[6] = cx - s * 0.42f; py[6] = cy
        g.fillPolygon(px, py, 7, color)
    }

    private fun calendar(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.42f, cy - s * 0.32f, s * 0.84f, s * 0.74f, s * 0.1f, color)
        g.fillRect(cx - s * 0.25f, cy - s * 0.45f, s * 0.08f, s * 0.2f, color)
        g.fillRect(cx + s * 0.17f, cy - s * 0.45f, s * 0.08f, s * 0.2f, color)
    }

    private fun infinity(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.strokeCircle(cx - s * 0.22f, cy, s * 0.2f, color, s * 0.1f)
        g.strokeCircle(cx + s * 0.22f, cy, s * 0.2f, color, s * 0.1f)
    }

    private fun play(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        px[0] = cx - s * 0.3f; py[0] = cy - s * 0.4f
        px[1] = cx + s * 0.42f; py[1] = cy
        px[2] = cx - s * 0.3f; py[2] = cy + s * 0.4f
        g.fillPolygon(px, py, 3, color)
    }

    private fun pauseIcon(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.32f, cy - s * 0.4f, s * 0.22f, s * 0.8f, s * 0.05f, color)
        g.fillRoundRect(cx + s * 0.1f, cy - s * 0.4f, s * 0.22f, s * 0.8f, s * 0.05f, color)
    }

    private fun cross(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.line(cx - s * 0.35f, cy - s * 0.35f, cx + s * 0.35f, cy + s * 0.35f, color, s * 0.16f)
        g.line(cx - s * 0.35f, cy + s * 0.35f, cx + s * 0.35f, cy - s * 0.35f, color, s * 0.16f)
    }

    private fun undoIcon(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.strokeCircle(cx + s * 0.05f, cy + s * 0.05f, s * 0.32f, color, s * 0.13f)
        px[0] = cx - s * 0.45f; py[0] = cy - s * 0.25f
        px[1] = cx - s * 0.05f; py[1] = cy - s * 0.48f
        px[2] = cx - s * 0.05f; py[2] = cy - s * 0.02f
        g.fillPolygon(px, py, 3, color)
    }

    companion object {
        // Olive drab and khaki, signal yellow, paper and red ink.
        val BG = 0xFF191C14.toInt()
        val BG_DEEP = 0xFF11140D.toInt()
        val BG_LIT = 0xFF2A3120.toInt()
        val CONTOUR = 0xFF7E8B5A.toInt()
        val PANEL = 0xFF2E3524.toInt()
        val PANEL_LIGHT = 0xFF3A4330.toInt()
        val PANEL_HI = 0xFF4A553C.toInt()
        val PANEL_DARK = 0xFF1E2318.toInt()
        val BAND = 0xFF22281A.toInt()
        val WELL = 0xFF1A1E14.toInt()
        val EDGE = 0xFF0F120B.toInt()
        val EMPTY = 0xFF151810.toInt()
        val TEXT = 0xFFF3EBD3.toInt()
        val DIM = 0xFFA6A58A.toInt()
        val FADED = 0xFF70735F.toInt()
        val DARK_TEXT = 0xFF22200F.toInt()
        val ACCENT = 0xFFF2B634.toInt()
        val ACCENT_LIT = 0xFFFFD978.toInt()
        val ACCENT_DOWN = 0xFFD99A1C.toInt()
        val ACCENT_DARK = 0xFF9A6A10.toInt()
        val OLIVE = 0xFF4D5940.toInt()
        val OLIVE_LIT = 0xFF68754F.toInt()
        val OLIVE_DOWN = 0xFF414B34.toInt()
        val OLIVE_DARK = 0xFF232919.toInt()
        val OFF_FACE = 0xFF2A3022.toInt()
        val DANGER = 0xFFC2412F.toInt()
        val DANGER_LIT = 0xFFE36A55.toInt()
        val DANGER_DOWN = 0xFFA83626.toInt()
        val DANGER_DARK = 0xFF6E2116.toInt()
        val STRIPE = 0xFF1C1E14.toInt()
        val STEEL = 0xFF9AA0A6.toInt()
        val STEEL_DARK = 0xFF5C6268.toInt()
        val GOLD = 0xFFFFD24A.toInt()
        val RED = 0xFFFF5A44.toInt()
        val GREEN = 0xFF9BD46A.toInt()
        val BLUE = 0xFF7FC4FF.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val PAPER = 0xFFE9DFC2.toInt()
        val PAPER_EDGE = 0xFFB8AA84.toInt()
        val INK = 0xFF2B2617.toInt()
        val STAMP = 0xFFC0392B.toInt()
        val SCRIM = 0xC80A0C07.toInt()

        /** How far below the middle the baseline sits, per font size: half the capital height. */
        private val MIDDLE = floatArrayOf(0.35f, 0.33f, 0.33f)

        /** An atlas icon square is this much larger than the shape on it. */
        private const val ICON_SCALE = 1.4f

        /** Hills of the menu map: x and y as parts of the screen, radius in dp, height. */
        private val HILLS = floatArrayOf(
            0.12f, 0.10f, 150f, 0.95f,
            0.88f, 0.26f, 190f, 0.85f,
            0.28f, 0.58f, 170f, 1.05f,
            0.92f, 0.80f, 210f, 0.9f,
            0.50f, 0.98f, 140f, 0.7f,
            0.62f, 0.40f, 110f, 0.55f,
        )
    }
}
