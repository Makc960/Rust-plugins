package com.makc.basedefense.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class Style { PRIMARY, NORMAL, GHOST, DANGER }

/**
 * A tiny immediate-mode toolkit: every frame the screens draw their buttons,
 * sliders and scroll lists again, and touches are matched against what was drawn.
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
            g.fillRoundRect(bx, r.y + r.h - d(3f), bw, d(2.5f), d(1.2f), Colors.alpha(WHITE, 0.25f))
        } else {
            val bh = r.h * r.h / (r.h + limit)
            val by = r.y + (r.h - bh) * (off / limit)
            g.fillRoundRect(r.x + r.w - d(3.5f), by, d(2.5f), bh, d(1.2f), Colors.alpha(WHITE, 0.25f))
        }
    }

    fun resetScroll(id: String) {
        offsets.remove(id)
    }

    fun scrollTo(id: String, value: Float) {
        offsets[id] = value
    }

    // --- text -----------------------------------------------------------------------------

    fun text(g: Gfx, s: String, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT, maxW: Float = 0f) {
        var sz = size
        if (maxW > 0f) {
            val tw = g.textWidth(s, sz)
            if (tw > maxW) sz *= maxW / tw
        }
        g.text(s, x, y, sz, color, align)
    }

    /** Text whose middle sits on [cy]. */
    fun textMid(g: Gfx, s: String, x: Float, cy: Float, size: Float, color: Int, align: Align = Align.CENTER, maxW: Float = 0f) {
        var sz = size
        if (maxW > 0f) {
            val tw = g.textWidth(s, sz)
            if (tw > maxW) sz *= maxW / tw
        }
        g.text(s, x, cy + sz * 0.36f, sz, color, align)
    }

    /** Splits [s] into lines no wider than [maxW]. */
    fun lines(g: Gfx, s: String, size: Float, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && g.textWidth(next, size) > maxW) {
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
    fun paragraph(g: Gfx, s: String, x: Float, y: Float, maxW: Float, size: Float, color: Int, align: Align = Align.LEFT, spacing: Float = 1.35f): Float {
        val ls = lines(g, s, size, maxW)
        val step = size * spacing
        for ((i, l) in ls.withIndex()) g.text(l, x, y + size + i * step, size, color, align)
        return size + (ls.size - 1) * step + size * 0.3f
    }

    // --- controls -------------------------------------------------------------------------

    /** An invisible pressable area. */
    fun area(id: String, x: Float, y: Float, aw: Float, ah: Float, enabled: Boolean = true, silent: Boolean = false, action: () -> Unit) {
        buttons += Btn(id, Rect(x, y, aw, ah), clip, enabled, silent, action)
    }

    fun down(id: String): Boolean = pressed == id

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
        icon: ((Gfx, Float, Float, Float, Int) -> Unit)? = null,
        action: () -> Unit,
    ) {
        area(id, x, y, bw, bh, enabled, action = action)
        val isDown = pressed == id
        val radius = min(d(10f), bh / 2f)
        val sink = if (isDown) d(2f) else 0f
        when {
            !enabled -> {
                g.fillRoundRect(x, y, bw, bh, radius, PANEL_LIGHT)
                g.strokeRoundRect(x, y, bw, bh, radius, EDGE, d(1f))
            }
            style == Style.PRIMARY -> {
                g.fillRoundRect(x, y + d(3f), bw, bh, radius, ACCENT_DARK)
                g.fillRoundRect(x, y + sink, bw, bh, radius, if (isDown) ACCENT_DOWN else ACCENT)
            }
            style == Style.DANGER -> {
                g.fillRoundRect(x, y + d(3f), bw, bh, radius, 0xFF6A1E1E.toInt())
                g.fillRoundRect(x, y + sink, bw, bh, radius, if (isDown) 0xFFB83030.toInt() else 0xFFD23C3C.toInt())
            }
            style == Style.NORMAL -> {
                g.fillRoundRect(x, y + d(3f), bw, bh, radius, 0xFF0A0E0C.toInt())
                g.fillRoundRect(x, y + sink, bw, bh, radius, if (isDown) PANEL else PANEL_LIGHT)
                g.strokeRoundRect(x, y + sink, bw, bh, radius, EDGE, d(1f))
            }
            else -> {
                if (isDown) g.fillRoundRect(x, y, bw, bh, radius, Colors.alpha(WHITE, 0.08f))
                g.strokeRoundRect(x, y, bw, bh, radius, EDGE, d(1.2f))
            }
        }
        val color = when {
            !enabled -> DIM
            style == Style.PRIMARY -> 0xFF1E1404.toInt()
            else -> TEXT
        }
        if (icon != null && label.isEmpty()) {
            icon(g, x + bw / 2f, y + bh / 2f + sink, min(bw, bh) * 0.5f, color)
        } else if (icon != null) {
            val s = min(bh * 0.46f, d(20f))
            val tw = min(g.textWidth(label, d(size)), bw - s - d(26f))
            val left = x + (bw - tw - s - d(8f)) / 2f
            icon(g, left + s / 2f, y + bh / 2f + sink, s, color)
            textMid(g, label, left + s + d(8f), y + bh / 2f + sink, d(size), color, Align.LEFT, maxW = bw - s - d(26f))
        } else {
            textMid(g, label, x + bw / 2f, y + bh / 2f + sink, d(size), color, maxW = bw - d(14f))
        }
    }

    fun toggle(g: Gfx, id: String, x: Float, y: Float, tw: Float, th: Float, label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        area(id, x, y, tw, th, enabled) { onChange(!value) }
        if (pressed == id) g.fillRoundRect(x, y, tw, th, d(10f), Colors.alpha(WHITE, 0.05f))
        textMid(g, label, x + d(4f), y + th / 2f, d(15f), if (enabled) TEXT else DIM, Align.LEFT, maxW = tw - d(70f))
        val sw = d(46f)
        val sh = d(26f)
        val sx = x + tw - sw - d(4f)
        val sy = y + (th - sh) / 2f
        g.fillRoundRect(sx, sy, sw, sh, sh / 2f, if (value && enabled) ACCENT else PANEL_LIGHT)
        g.strokeRoundRect(sx, sy, sw, sh, sh / 2f, EDGE, d(1f))
        val kx = if (value) sx + sw - sh / 2f else sx + sh / 2f
        g.fillCircle(kx, sy + sh / 2f, sh / 2f - d(3f), if (enabled) TEXT else DIM)
    }

    /** A 0-100 slider; [onChange] gets the new value. */
    fun slider(g: Gfx, id: String, x: Float, y: Float, sw: Float, sh: Float, label: String, value: Int, onChange: (Int) -> Unit) {
        textMid(g, label, x + d(4f), y + d(12f), d(15f), TEXT, Align.LEFT, maxW = sw * 0.7f)
        textMid(g, "$value%", x + sw - d(4f), y + d(12f), d(14f), DIM, Align.RIGHT)
        val ty = y + sh - d(16f)
        val left = x + d(12f)
        val width = sw - d(24f)
        sliders += Slider(id, Rect(x, y + d(18f), sw, sh - d(18f))) { f -> onChange(Math.round(((f * sw - d(12f)) / width).coerceIn(0f, 1f) * 100f)) }
        g.fillRoundRect(left, ty - d(3f), width, d(6f), d(3f), PANEL_LIGHT)
        g.fillRoundRect(left, ty - d(3f), width * value / 100f, d(6f), d(3f), ACCENT)
        g.fillCircle(left + width * value / 100f, ty, d(11f), if (slider == id) ACCENT_DOWN else TEXT)
    }

    /** A row of options where one is chosen. */
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
        val gap = d(6f)
        val each = (sw - gap * (options.size - 1)) / options.size
        for ((i, o) in options.withIndex()) {
            val bx = x + i * (each + gap)
            val on = i == selected
            val ok = enabled(i)
            area("$id$i", bx, y, each, sh, ok) { onSelect(i) }
            val isDown = pressed == "$id$i"
            g.fillRoundRect(bx, y + (if (isDown) d(1.5f) else 0f), each, sh, d(9f), if (on) Colors.alpha(ACCENT, 0.22f) else PANEL_LIGHT)
            g.strokeRoundRect(bx, y, each, sh, d(9f), if (on) ACCENT else EDGE, if (on) d(2f) else d(1f))
            textMid(g, o, bx + each / 2f, y + sh / 2f, d(size), if (!ok) DIM else if (on) ACCENT else TEXT, maxW = each - d(10f))
            if (!ok) lock(g, bx + each - d(12f), y + d(11f), d(12f))
        }
    }

    fun panel(g: Gfx, x: Float, y: Float, pw: Float, ph: Float, edge: Int = EDGE) {
        g.fillRoundRect(x, y + d(4f), pw, ph, d(18f), 0xFF050706.toInt())
        g.fillRoundRect(x, y, pw, ph, d(18f), PANEL)
        g.strokeRoundRect(x, y, pw, ph, d(18f), edge, d(1.2f))
    }

    fun header(g: Gfx, title: String, onBack: () -> Unit): Float {
        val y = top + d(10f)
        button(g, "back", d(12f), y, d(46f), d(42f), "", icon = { gg, cx, cy, s, c -> chevron(gg, cx, cy, s, c) }, action = onBack)
        textMid(g, title, w / 2f, y + d(21f), d(20f), TEXT, maxW = w - d(140f))
        return y + d(54f)
    }

    // --- icons ----------------------------------------------------------------------------

    private val px = FloatArray(12)
    private val py = FloatArray(12)

    fun star(g: Gfx, cx: Float, cy: Float, r: Float, filled: Boolean, color: Int = GOLD) {
        for (k in 0 until 10) {
            val a = -PI / 2 + k * PI / 5
            val rr = if (k % 2 == 0) r else r * 0.45f
            px[k] = cx + (cos(a) * rr).toFloat()
            py[k] = cy + (sin(a) * rr).toFloat()
        }
        g.fillPolygon(px, py, 10, if (filled) color else 0xFF3A4440.toInt())
    }

    fun heart(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillCircle(cx - s * 0.27f, cy - s * 0.12f, s * 0.3f, color)
        g.fillCircle(cx + s * 0.27f, cy - s * 0.12f, s * 0.3f, color)
        px[0] = cx - s * 0.55f; py[0] = cy - s * 0.02f
        px[1] = cx + s * 0.55f; py[1] = cy - s * 0.02f
        px[2] = cx; py[2] = cy + s * 0.55f
        g.fillPolygon(px, py, 3, color)
    }

    fun coin(g: Gfx, cx: Float, cy: Float, s: Float) {
        g.fillCircle(cx, cy + s * 0.06f, s * 0.5f, 0xFF9C7A16.toInt())
        g.fillCircle(cx, cy, s * 0.5f, GOLD)
        g.strokeCircle(cx, cy, s * 0.34f, 0xFFC99A1E.toInt(), s * 0.08f)
    }

    fun lock(g: Gfx, cx: Float, cy: Float, s: Float, color: Int = DIM) {
        g.strokeCircle(cx, cy - s * 0.18f, s * 0.24f, color, s * 0.1f)
        g.fillRoundRect(cx - s * 0.36f, cy - s * 0.12f, s * 0.72f, s * 0.56f, s * 0.08f, color)
        g.fillCircle(cx, cy + s * 0.12f, s * 0.08f, PANEL)
    }

    /** The mark of a hard win. */
    fun skull(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillCircle(cx, cy - s * 0.08f, s * 0.42f, color)
        g.fillRoundRect(cx - s * 0.26f, cy + s * 0.1f, s * 0.52f, s * 0.3f, s * 0.08f, color)
        g.fillCircle(cx - s * 0.16f, cy - s * 0.06f, s * 0.11f, PANEL)
        g.fillCircle(cx + s * 0.16f, cy - s * 0.06f, s * 0.11f, PANEL)
        g.fillRect(cx - s * 0.03f, cy + s * 0.2f, s * 0.06f, s * 0.2f, PANEL)
    }

    /** The mark of a won challenge. */
    fun medal(g: Gfx, cx: Float, cy: Float, s: Float, filled: Boolean) {
        val ribbon = if (filled) 0xFFD23C3C.toInt() else 0xFF3A4440.toInt()
        px[0] = cx - s * 0.32f; py[0] = cy - s * 0.5f
        px[1] = cx - s * 0.08f; py[1] = cy - s * 0.5f
        px[2] = cx + s * 0.05f; py[2] = cy
        px[3] = cx - s * 0.18f; py[3] = cy
        g.fillPolygon(px, py, 4, ribbon)
        px[0] = cx + s * 0.32f; py[0] = cy - s * 0.5f
        px[1] = cx + s * 0.08f; py[1] = cy - s * 0.5f
        px[2] = cx - s * 0.05f; py[2] = cy
        px[3] = cx + s * 0.18f; py[3] = cy
        g.fillPolygon(px, py, 4, Colors.darker(ribbon, 0.2f))
        g.fillCircle(cx, cy + s * 0.15f, s * 0.32f, if (filled) GOLD else 0xFF3A4440.toInt())
        if (filled) star(g, cx, cy + s * 0.15f, s * 0.18f, true, 0xFFB8860B.toInt())
    }

    fun chevron(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.line(cx + s * 0.18f, cy - s * 0.4f, cx - s * 0.22f, cy, color, s * 0.18f)
        g.line(cx - s * 0.22f, cy, cx + s * 0.18f, cy + s * 0.4f, color, s * 0.18f)
    }

    fun gear(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        for (k in 0 until 8) {
            val a = k * PI.toFloat() / 4f
            g.line(cx + cos(a) * s * 0.25f, cy + sin(a) * s * 0.25f, cx + cos(a) * s * 0.5f, cy + sin(a) * s * 0.5f, color, s * 0.2f)
        }
        g.fillCircle(cx, cy, s * 0.36f, color)
        g.fillCircle(cx, cy, s * 0.15f, PANEL_LIGHT)
    }

    fun trophy(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.32f, cy - s * 0.45f, s * 0.64f, s * 0.5f, s * 0.2f, color)
        g.strokeCircle(cx - s * 0.32f, cy - s * 0.25f, s * 0.14f, color, s * 0.07f)
        g.strokeCircle(cx + s * 0.32f, cy - s * 0.25f, s * 0.14f, color, s * 0.07f)
        g.fillRect(cx - s * 0.06f, cy, s * 0.12f, s * 0.25f, color)
        g.fillRoundRect(cx - s * 0.26f, cy + s * 0.25f, s * 0.52f, s * 0.15f, s * 0.05f, color)
    }

    fun book(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.42f, cy - s * 0.38f, s * 0.4f, s * 0.76f, s * 0.06f, color)
        g.fillRoundRect(cx + s * 0.02f, cy - s * 0.38f, s * 0.4f, s * 0.76f, s * 0.06f, color)
        g.line(cx - s * 0.32f, cy - s * 0.15f, cx - s * 0.1f, cy - s * 0.15f, PANEL_LIGHT, s * 0.06f)
        g.line(cx + s * 0.12f, cy - s * 0.15f, cx + s * 0.34f, cy - s * 0.15f, PANEL_LIGHT, s * 0.06f)
        g.line(cx - s * 0.32f, cy + s * 0.05f, cx - s * 0.1f, cy + s * 0.05f, PANEL_LIGHT, s * 0.06f)
        g.line(cx + s * 0.12f, cy + s * 0.05f, cx + s * 0.34f, cy + s * 0.05f, PANEL_LIGHT, s * 0.06f)
    }

    fun upgradeArrow(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        px[0] = cx; py[0] = cy - s * 0.5f
        px[1] = cx + s * 0.42f; py[1] = cy
        px[2] = cx + s * 0.16f; py[2] = cy
        px[3] = cx + s * 0.16f; py[3] = cy + s * 0.45f
        px[4] = cx - s * 0.16f; py[4] = cy + s * 0.45f
        px[5] = cx - s * 0.16f; py[5] = cy
        px[6] = cx - s * 0.42f; py[6] = cy
        g.fillPolygon(px, py, 7, color)
    }

    fun calendar(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.42f, cy - s * 0.32f, s * 0.84f, s * 0.74f, s * 0.1f, color)
        g.fillRect(cx - s * 0.42f, cy - s * 0.14f, s * 0.84f, s * 0.06f, PANEL_LIGHT)
        g.fillRect(cx - s * 0.25f, cy - s * 0.45f, s * 0.08f, s * 0.2f, color)
        g.fillRect(cx + s * 0.17f, cy - s * 0.45f, s * 0.08f, s * 0.2f, color)
        g.fillRect(cx - s * 0.2f, cy + s * 0.02f, s * 0.14f, s * 0.12f, PANEL_LIGHT)
    }

    fun infinity(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.strokeCircle(cx - s * 0.22f, cy, s * 0.2f, color, s * 0.1f)
        g.strokeCircle(cx + s * 0.22f, cy, s * 0.2f, color, s * 0.1f)
    }

    fun play(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        px[0] = cx - s * 0.3f; py[0] = cy - s * 0.4f
        px[1] = cx + s * 0.42f; py[1] = cy
        px[2] = cx - s * 0.3f; py[2] = cy + s * 0.4f
        g.fillPolygon(px, py, 3, color)
    }

    fun pauseIcon(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillRoundRect(cx - s * 0.32f, cy - s * 0.4f, s * 0.22f, s * 0.8f, s * 0.05f, color)
        g.fillRoundRect(cx + s * 0.1f, cy - s * 0.4f, s * 0.22f, s * 0.8f, s * 0.05f, color)
    }

    fun cross(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.line(cx - s * 0.35f, cy - s * 0.35f, cx + s * 0.35f, cy + s * 0.35f, color, s * 0.16f)
        g.line(cx - s * 0.35f, cy + s * 0.35f, cx + s * 0.35f, cy - s * 0.35f, color, s * 0.16f)
    }

    fun undoIcon(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.strokeCircle(cx + s * 0.05f, cy + s * 0.05f, s * 0.32f, color, s * 0.13f)
        g.fillRect(cx - s * 0.5f, cy - s * 0.5f, s * 0.5f, s * 0.42f, PANEL_LIGHT)
        px[0] = cx - s * 0.45f; py[0] = cy - s * 0.25f
        px[1] = cx - s * 0.05f; py[1] = cy - s * 0.48f
        px[2] = cx - s * 0.05f; py[2] = cy - s * 0.02f
        g.fillPolygon(px, py, 3, color)
    }

    fun bar(g: Gfx, x: Float, y: Float, bw: Float, bh: Float, f: Float, color: Int) {
        g.fillRoundRect(x, y, bw, bh, bh / 2f, PANEL_LIGHT)
        if (f > 0f) g.fillRoundRect(x, y, max(bh, bw * f.coerceIn(0f, 1f)), bh, bh / 2f, color)
    }

    companion object {
        val BG = 0xFF0E1412.toInt()
        val PANEL = 0xFF151C19.toInt()
        val PANEL_LIGHT = 0xFF1F2925.toInt()
        val EDGE = 0xFF33413B.toInt()
        val TEXT = 0xFFF1EDE4.toInt()
        val DIM = 0xFF8E9A93.toInt()
        val ACCENT = 0xFFFF9F1A.toInt()
        val ACCENT_DOWN = 0xFFE88A0A.toInt()
        val ACCENT_DARK = 0xFF9C5E0B.toInt()
        val GOLD = 0xFFFFD54A.toInt()
        val RED = 0xFFFF4B4B.toInt()
        val GREEN = 0xFF6BD66B.toInt()
        val BLUE = 0xFF7FC4FF.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val SCRIM = 0xB3000000.toInt()
    }
}
