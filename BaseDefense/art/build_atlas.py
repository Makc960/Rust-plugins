#!/usr/bin/env python3
"""
Builds the game's sprite atlas from Kenney's CC0 asset packs (kenney.nl).

The vector sheets of "Tower Defense (top-down)" and "Top-down Shooter" are
rendered at 4x with headless Chromium (render_svg.js), every named sprite is
found in those renders, turned to face right, recoloured where a world needs
it (snow, sandstone, a purple ghost...) and packed with a few pieces drawn
here in the same flat style. The towers are squads of the shooter pack's
soldier, each squad in its own colour, each job with its own weapon. Output: engine/src/main/resources/sprites/
atlas.png and atlas.txt (name x y w h per line).

usage: build_atlas.py KENNEY_ROOT WORK_DIR OUT_DIR [--preview FILE]
KENNEY_ROOT is a checkout of github.com/ETdoFresh/kenney.nl, a mirror of the packs.
"""
import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

PX = 128          # atlas pixels per map cell
HI = 4            # custom drawings are made this many times larger, then shrunk
U = HI            # one atlas pixel on a drawing canvas

ap = argparse.ArgumentParser()
ap.add_argument('kenney')
ap.add_argument('work')
ap.add_argument('out')
ap.add_argument('--preview')
args = ap.parse_args()
K = Path(args.kenney)
W = Path(args.work)
W.mkdir(parents=True, exist_ok=True)
OUT = Path(args.out)
OUT.mkdir(parents=True, exist_ok=True)
HERE = Path(__file__).resolve().parent


# --- sources ----------------------------------------------------------------------------

def render(svg, name, scale, w, h):
    out = W / name
    if not out.exists():
        subprocess.run(['node', str(HERE / 'render_svg.js'), str(svg), str(out), str(scale), str(w), str(h)], check=True)
    return Image.open(out).convert('RGBA')


TD_DIR = K / 'tower-defense-top-down'
td_sheet = render(TD_DIR / 'Vector/towerDefense_vector.svg', 'td4x.png', 4, 1472, 832)
chars_sheet = render(K / 'topdown-shooter/Vector/vector_characters.svg', 'chars4x.png', 4, 400, 700)
TANKS = K / 'kenney_topdowntanksredux/PNG/Retina'


def premul(a):
    return np.concatenate([a[..., :3] * a[..., 3:4] / 255.0, a[..., 3:4]], axis=-1)


def td_cells():
    """Where each numbered tile of the pack sits in the vector sheet (23 x 13 cells)."""
    cache = W / 'td4x.json'
    if cache.exists():
        return {int(k): v for k, v in json.loads(cache.read_text()).items()}
    small = np.asarray(td_sheet.resize((1472, 832), Image.LANCZOS)).astype(np.float32)
    cells = np.stack([small[r * 64:(r + 1) * 64, c * 64:(c + 1) * 64] for r in range(13) for c in range(23)])
    cells = premul(cells)
    out = {}
    for k in range(1, 300):
        f = TD_DIR / ('PNG/Default size/towerDefense_tile%03d.png' % k)
        if not f.exists():
            continue
        t = premul(np.asarray(Image.open(f).convert('RGBA')).astype(np.float32))
        i = int(np.abs(cells - t[None]).mean(axis=(1, 2, 3)).argmin())
        out[k] = [i % 23, i // 23]
    cache.write_text(json.dumps(out))
    return out


def char_boxes():
    """Bounding box of every named character in the vector render, at 4x."""
    cache = W / 'chars4x.json'
    if cache.exists():
        return json.loads(cache.read_text())
    small = np.asarray(chars_sheet.resize((chars_sheet.width // 4, chars_sheet.height // 4), Image.LANCZOS)).astype(float)
    alpha = small[:, :, 3] > 8

    def bands(profile, gap=3):
        out, start, empty = [], None, 0
        for i, v in enumerate(profile):
            if v:
                if start is None:
                    start = i
                empty = 0
            elif start is not None:
                empty += 1
                if empty > gap:
                    out.append((start, i - empty + 1))
                    start, empty = None, 0
        if start is not None:
            out.append((start, len(profile)))
        return out

    cells = []
    for (r0, r1) in bands(alpha.any(axis=1)):
        for (c0, c1) in bands(alpha[r0:r1].any(axis=0)):
            cells.append((c0, r0, c1 - c0, r1 - r0))
    out = {}
    for f in sorted((K / 'topdown-shooter/PNG').glob('*/*.png')):
        if f.parent.name == 'Tiles':
            continue
        t = np.asarray(Image.open(f).convert('RGBA')).astype(float)
        th, tw = t.shape[:2]
        best = None
        for (x, y, w, h) in cells:
            x0, y0 = int(round(x + w / 2 - tw / 2)), int(round(y + h / 2 - th / 2))
            for dx in range(-4, 5):
                for dy in range(-4, 5):
                    xx, yy = x0 + dx, y0 + dy
                    if xx < 0 or yy < 0 or xx + tw > small.shape[1] or yy + th > small.shape[0]:
                        continue
                    d = np.abs(small[yy:yy + th, xx:xx + tw] - t).mean()
                    if best is None or d < best[0]:
                        best = (d, xx, yy, tw, th)
        out[f.stem] = [best[1] * 4, best[2] * 4, best[3] * 4, best[4] * 4]
    cache.write_text(json.dumps(out))
    return out


TD = td_cells()
CHARS = char_boxes()


def td(k):
    """Tower defense tile k as a cell-sized picture (PX square)."""
    c, r = TD[k]
    return td_sheet.crop((c * 256, r * 256, c * 256 + 256, r * 256 + 256)).resize((PX, PX), Image.LANCZOS)


def tanks(name):
    return Image.open(TANKS / (name + '.png')).convert('RGBA')


def character(name, k=1.0):
    """A character from the shooter pack, centred on its head, facing right."""
    x, y, w, h = CHARS[name]
    px, py = x + 66, y + 86      # the head: the arms and guns stick out to the right
    m = 6
    half = max(px - x, x + w - px, py - y, y + h - py) + m + 2
    # Only this character's own box, so a neighbour on the sheet never leaks in.
    body = chars_sheet.crop((x - m, y - m, x + w + m, y + h + m))
    im = Image.new('RGBA', (2 * half, 2 * half))
    im.alpha_composite(body, (x - m - (px - half), y - m - (py - half)))
    size = int(round(2 * half * PX / 256 * k))
    return im.resize((size, size), Image.LANCZOS)


def face_right_from_up(im):
    return im.transpose(Image.Transpose.ROTATE_270)


def face_right_from_down(im):
    return im.transpose(Image.Transpose.ROTATE_90)


def trim(im, pad=2):
    box = im.getchannel('A').point(lambda a: 255 if a > 4 else 0).getbbox()
    if not box:
        return im
    l, t, r, b = box
    return im.crop((max(0, l - pad), max(0, t - pad), min(im.width, r + pad), min(im.height, b + pad)))


def square(im, size=None):
    """Centres [im] on a transparent square, so it can turn around its middle."""
    s = size or max(im.width, im.height)
    out = Image.new('RGBA', (s, s))
    out.alpha_composite(im, ((s - im.width) // 2, (s - im.height) // 2))
    return out


def scale(im, k):
    return im.resize((max(1, int(round(im.width * k))), max(1, int(round(im.height * k)))), Image.LANCZOS)


def shadowed(im, dx=4, dy=6, blur=3, alpha=0.3):
    """Adds a soft shadow down and to the right, keeping the picture centred."""
    pad = max(abs(dx), abs(dy)) + blur * 2
    out = Image.new('RGBA', (im.width + 2 * pad, im.height + 2 * pad))
    sh = Image.new('RGBA', im.size, (0, 0, 0, 255))
    sh.putalpha(im.getchannel('A').point(lambda v: int(v * alpha)))
    layer = Image.new('RGBA', out.size)
    layer.alpha_composite(sh, (pad + dx, pad + dy))
    out.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))
    out.alpha_composite(im, (pad, pad))
    return out


# --- colour --------------------------------------------------------------------------

def rgb_to_hsv(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx, mn = rgb.max(-1), rgb.min(-1)
    d = mx - mn
    nz = d > 1e-6
    rc = np.where(nz, (mx - r) / np.where(nz, d, 1), 0)
    gc = np.where(nz, (mx - g) / np.where(nz, d, 1), 0)
    bc = np.where(nz, (mx - b) / np.where(nz, d, 1), 0)
    h = np.where(r == mx, bc - gc, np.where(g == mx, 2.0 + rc - bc, 4.0 + gc - rc))
    h = np.where(nz, (h / 6.0) % 1.0, 0)
    s = np.where(mx > 1e-6, d / np.where(mx > 1e-6, mx, 1), 0)
    return h, s, mx


def hsv_to_rgb(h, s, v):
    i = np.floor(h * 6.0)
    f = h * 6.0 - i
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    i = i.astype(int) % 6
    r = np.choose(i, [v, q, p, p, t, v])
    g = np.choose(i, [t, v, v, q, p, p])
    b = np.choose(i, [p, p, t, v, v, q])
    return np.stack([r, g, b], axis=-1)


def recolor(im, fn):
    a = np.asarray(im).astype(np.float32) / 255.0
    h, s, v = rgb_to_hsv(a[..., :3])
    h2, s2, v2 = fn(h, s, v)
    rgb = hsv_to_rgb(np.clip(h2, 0, 1) % 1.0, np.clip(s2, 0, 1), np.clip(v2, 0, 1))
    out = np.concatenate([rgb, a[..., 3:4]], axis=-1)
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')


def greens(h, s):
    return (h > 0.2) & (h < 0.5) & (s > 0.2)


def snow(im):
    """Grass into snow: the same light and shade, white with a cold tint."""
    def fn(h, s, v):
        m = greens(h, s)
        v2 = np.clip(0.9 + (v - 0.72) * 1.1, 0.72, 0.99)
        return np.where(m, 0.58, h), np.where(m, 0.035 + (0.99 - v2) * 0.35, s), np.where(m, v2, v)
    return recolor(im, fn)


def sandstone(im):
    def fn(h, s, v):
        m = s < 0.4
        return np.where(m, 0.07, h), np.where(m, 0.3 + s * 0.6, s), np.where(m, v * 0.97, v)
    return recolor(im, fn)


def snowcap(im):
    def fn(h, s, v):
        light = v > 0.72
        return np.where(light, 0.58, 0.6), np.where(light, 0.04, 0.12), np.where(light, 0.98, v * 0.85)
    return recolor(im, fn)


def pine(im):
    """A summer tree into a dark winter one."""
    def fn(h, s, v):
        m = greens(h, s)
        return np.where(m, 0.42, h), np.where(m, 0.55, s), np.where(m, v * 0.5, v)
    return recolor(im, fn)


def snow_on(im, dx=-5, dy=-6, blobs=0):
    """White snow on the sunny side: where the shape, moved up-left, still covers itself."""
    a = np.asarray(im.getchannel('A')).astype(np.float32) / 255.0
    moved = np.zeros_like(a)
    h, w = a.shape
    moved[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)] = \
        a[max(0, dy):h - max(0, -dy), max(0, dx):w - max(0, -dx)]
    cap = np.clip(a - moved, 0, 1)
    cap = np.asarray(Image.fromarray((cap * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))).astype(np.float32) / 255
    cap = np.clip(cap * 2.2, 0, 1) * a
    snow_layer = Image.new('RGBA', im.size, (244, 248, 252, 255))
    snow_layer.putalpha(Image.fromarray((cap * 255).astype(np.uint8)))
    out = im.copy()
    out.alpha_composite(snow_layer)
    if blobs:
        d = ImageDraw.Draw(out)
        rng = np.random.default_rng(blobs)
        cx, cy = im.width / 2, im.height / 2
        for _ in range(blobs):
            r = rng.uniform(2.5, 5)
            x = cx + rng.uniform(-0.3, 0.2) * im.width
            y = cy + rng.uniform(-0.3, 0.2) * im.height
            if a[int(y), int(x)] > 0.9:
                d.ellipse((x - r, y - r, x + r, y + r), fill=(244, 248, 252, 255))
    return out


def hue(im, target, sat=None, light=1.0, only_saturated=True):
    """Moves every coloured pixel to [target] hue; greys stay unless only_saturated is False."""
    def fn(h, s, v):
        m = s > 0.18 if only_saturated else np.ones_like(s, dtype=bool)
        s2 = s if sat is None else np.where(m, sat, s)
        return np.where(m, target, h), np.where(m, s2, s), np.where(m, v * light, v)
    return recolor(im, fn)


def ghostly(im):
    def fn(h, s, v):
        dark = v < 0.5
        return np.where(dark, 0.76, h), np.where(dark, 0.5, s), np.where(dark, v * 1.2 + 0.18, v)
    return recolor(im, fn)


def white_coat(im):
    """The old man's black coat into a doctor's white one."""
    def fn(h, s, v):
        dark = (v < 0.42) & (s < 0.35)
        return np.where(dark, 0.58, h), np.where(dark, 0.06, s), np.where(dark, 0.78 + v * 0.5, v)
    return recolor(im, fn)


def rgba(hexcode, a=255):
    return ((hexcode >> 16) & 255, (hexcode >> 8) & 255, hexcode & 255, a)


def darker(c, k=0.62):
    return tuple(int(v * k) for v in c[:3]) + (255,)


def lighter(c, k=0.35):
    return tuple(int(v + (255 - v) * k) for v in c[:3]) + (255,)


# --- drawing in the packs' flat style ----------------------------------------------------

def canvas(cells=1.0):
    s = int(PX * HI * cells)
    im = Image.new('RGBA', (s, s))
    return im, ImageDraw.Draw(im), s / 2


def shrink(im):
    return im.resize((im.width // HI, im.height // HI), Image.LANCZOS)


def box(c, l, t, r, b):
    """A box in atlas pixels around the canvas middle."""
    return (c + l * U, c + t * U, c + r * U, c + b * U)


def part(d, kind, xy, fill, edge=None, width=4, radius=None):
    """A shape with the darker rim every pack sprite has; [width] in atlas pixels."""
    edge = edge or darker(fill)
    l, t, r, b = xy
    w = width * U
    if kind == 'round':
        rad = (radius if radius is not None else 4) * U
        d.rounded_rectangle((l, t, r, b), radius=rad, fill=edge)
        d.rounded_rectangle((l + w, t + w, r - w, b - w), radius=max(0, rad - w), fill=fill)
    else:
        getattr(d, kind)((l, t, r, b), fill=edge)
        getattr(d, kind)((l + w, t + w, r - w, b - w), fill=fill)


def disc(d, c, x, y, r, fill, edge=None, width=4):
    part(d, 'ellipse', box(c, x - r, y - r, x + r, y + r), fill, edge, width)


STEEL = (128, 136, 146, 255)
STEEL_DARK = (66, 72, 80, 255)
GOLD = (255, 200, 40, 255)


def radar_dish(dish=(214, 224, 230, 255)):
    """A dish on a turning mount, seen from above, facing right."""
    im, d, c = canvas()
    disc(d, c, 0, 0, 16, (88, 96, 104, 255), width=4)
    # Struts from the rim to the feed in front.
    for y in (-30, 30):
        d.line(box(c, 6, y, 34, 0)[:4], fill=STEEL_DARK, width=int(3 * U))
    part(d, 'ellipse', box(c, -10, -44, 22, 44), dish, (112, 124, 134, 255), width=4)
    d.ellipse(box(c, -2, -35, 17, 35), fill=(186, 198, 208, 255))
    disc(d, c, 36, 0, 6, (230, 72, 60, 255), width=2)
    return shrink(im)


def jammer():
    im, d, c = canvas()
    disc(d, c, 0, 0, 23, (80, 150, 142, 255), width=4)
    for r in (37, 29):
        for start in (-40, 140):
            d.arc(box(c, -r, -r, r, r), start, start + 80, fill=(95, 168, 160, 255), width=int(3.2 * U))
    disc(d, c, 0, 0, 8, (60, 66, 72, 255), width=2)
    d.ellipse(box(c, -3.5, -3.5, 3.5, 3.5), fill=(255, 70, 60, 255))
    return shrink(im)


def mine_cart():
    """The quarry: a cart of gold ore on a short stretch of rails."""
    im, d, c = canvas()
    for x in range(-44, 45, 15):
        part(d, 'round', box(c, x - 4, -34, x + 4, 34), (128, 88, 54, 255), width=2, radius=1)
    for y in (-24, 24):
        part(d, 'round', box(c, -52, y - 3, 52, y + 3), (150, 156, 164, 255), width=1.5, radius=1)
    part(d, 'round', box(c, -34, -30, 34, 30), (158, 98, 58, 255), width=4, radius=6)
    d.rounded_rectangle(box(c, -25, -21, 25, 21), radius=4 * U, fill=(78, 50, 32, 255))
    rng = np.random.default_rng(7)
    for _ in range(16):
        x, y = rng.uniform(-19, 19), rng.uniform(-15, 15)
        disc(d, c, x, y, rng.uniform(4.5, 7), GOLD, (190, 132, 20, 255), width=1.6)
    return shrink(im)


def derrick():
    """The mine shaft: a steel frame over a dark hole."""
    im, d, c = canvas()
    disc(d, c, 0, 0, 22, (40, 30, 24, 255), (26, 20, 16, 255), width=4)
    frame = (92, 100, 110, 255)
    for (x0, y0, x1, y1) in ((-34, -34, 34, -34), (34, -34, 34, 34), (34, 34, -34, 34), (-34, 34, -34, -34),
                             (-34, -34, 34, 34), (34, -34, -34, 34)):
        d.line(box(c, x0, y0, x1, y1), fill=STEEL_DARK, width=int(8 * U))
        d.line(box(c, x0, y0, x1, y1), fill=frame, width=int(4.5 * U))
    for (x, y) in ((-34, -34), (34, -34), (34, 34), (-34, 34)):
        disc(d, c, x, y, 7, (110, 118, 128, 255), width=2.5)
    return shrink(im)


def drill_bit():
    im, d, c = canvas(0.4)
    for k in range(3):
        a = math.radians(k * 120)
        pts = []
        for (r, da) in ((5, -50), (19, -12), (19, 12), (5, 50)):
            pts.append(box(c, math.cos(a + math.radians(da)) * r, math.sin(a + math.radians(da)) * r, 0, 0)[:2])
        d.polygon(pts, fill=(60, 64, 70, 255))
        inner = [((x - c) * 0.78 + c, (y - c) * 0.78 + c) for (x, y) in pts]
        d.polygon(inner, fill=(232, 186, 60, 255))
    disc(d, c, 0, 0, 7, (176, 186, 196, 255), width=2)
    return shrink(im)


def vault_dial():
    im, d, c = canvas(0.4)
    disc(d, c, 0, 0, 16, GOLD, (176, 120, 20, 255), width=3)
    for k in range(3):
        a = math.radians(k * 120)
        d.line(box(c, 0, 0, math.cos(a) * 21, math.sin(a) * 21), fill=(176, 120, 20, 255), width=int(4.5 * U))
        disc(d, c, math.cos(a) * 21, math.sin(a) * 21, 3.5, GOLD, (176, 120, 20, 255), width=1.5)
    disc(d, c, 0, 0, 6, (220, 160, 30, 255), (176, 120, 20, 255), width=1.5)
    return shrink(im)


def cactus():
    """A barrel cactus with two arms, seen from above."""
    im, d, c = canvas()
    green = (79, 158, 70, 255)
    dark = (44, 100, 40, 255)
    for (x, y, r) in ((-36, 20, 19), (34, -23, 17)):
        d.line(box(c, 0, 0, x, y), fill=dark, width=int(21 * U))
        d.line(box(c, 0, 0, x * 0.9, y * 0.9), fill=green, width=int(13 * U))
        disc(d, c, x, y, r, green, dark, width=3.5)
        for a in range(0, 360, 60):
            d.line(box(c, x, y, x + math.cos(math.radians(a)) * (r - 5), y + math.sin(math.radians(a)) * (r - 5)),
                   fill=(64, 134, 58, 255), width=int(2 * U))
    disc(d, c, 0, 0, 32, green, dark, width=4)
    for a in range(0, 360, 30):
        d.line(box(c, 0, 0, math.cos(math.radians(a)) * 26, math.sin(math.radians(a)) * 26),
               fill=(64, 134, 58, 255), width=int(2.2 * U))
    disc(d, c, 0, 0, 9, (240, 110, 150, 255), (190, 60, 100, 255), width=2)
    disc(d, c, 0, 0, 3, (255, 220, 120, 255), width=0.5)
    return trim(shrink(im))


def heli_body():
    """A helicopter seen from above, nose to the right; the rotor is drawn by the game."""
    im, d, c = canvas(1.7)
    body = (88, 104, 70, 255)
    dark = darker(body, 0.6)
    # Tail boom with the tail rotor.
    part(d, 'round', box(c, -96, -6, -20, 6), body, dark, width=2.5, radius=4)
    part(d, 'round', box(c, -100, -20, -90, 20), (70, 76, 82, 255), width=2, radius=3)
    part(d, 'round', box(c, -78, -12, -70, 12), body, dark, width=2, radius=2)
    # Skids and the stub wings with rockets.
    for y in (-30, 30):
        part(d, 'round', box(c, -26, y - 3, 38, y + 3), (70, 76, 82, 255), width=1.5, radius=2)
    part(d, 'round', box(c, -10, -42, 6, 42), body, dark, width=3, radius=3)
    for y in (-40, 40):
        part(d, 'round', box(c, -8, y - 4.5, 16, y + 4.5), (230, 232, 236, 255), (120, 124, 130, 255), width=1.5, radius=4)
        disc(d, c, 16, y, 3, (220, 64, 52, 255), width=1)
    # Fuselage and the glass.
    part(d, 'ellipse', box(c, -36, -24, 52, 24), body, dark, width=4)
    d.ellipse(box(c, -30, -18, 10, 0), fill=lighter(body, 0.2))
    part(d, 'ellipse', box(c, 18, -15, 50, 15), (150, 205, 236, 255), (60, 90, 110, 255), width=2.5)
    d.ellipse(box(c, 28, -11, 40, -3), fill=(225, 245, 255, 255))
    disc(d, c, -4, 0, 7, (50, 56, 62, 255), width=2)
    return shrink(im)


def riot_shield(im):
    """Puts a riot shield in front of a character (facing right)."""
    big = Image.new('RGBA', (im.width * HI, im.height * HI))
    d = ImageDraw.Draw(big)
    c = big.width / 2
    k = im.width / 138          # characters are about 138 atlas pixels across at natural size
    q = HI * k
    x0 = c + 20 * q
    d.rounded_rectangle((x0, c - 33 * q, x0 + 11 * q, c + 33 * q), radius=int(5 * q), fill=(36, 48, 62, 255))
    d.rounded_rectangle((x0 + 2.5 * q, c - 30 * q, x0 + 8.5 * q, c + 30 * q), radius=int(3.5 * q), fill=(96, 140, 186, 255))
    d.line((x0 + 5.5 * q, c - 22 * q, x0 + 5.5 * q, c + 22 * q), fill=(170, 210, 240, 255), width=max(1, int(2 * q)))
    out = im.copy()
    out.alpha_composite(shrink(big))
    return out


def red_cross(im):
    big = Image.new('RGBA', (im.width * HI, im.height * HI))
    d = ImageDraw.Draw(big)
    c = big.width / 2
    q = HI * im.width / 138
    cx = c - 3 * q
    d.ellipse((cx - 12 * q, c - 12 * q, cx + 12 * q, c + 12 * q), fill=(250, 250, 250, 255), outline=(200, 200, 200, 255), width=int(1.5 * q))
    d.rectangle((cx - 8 * q, c - 2.8 * q, cx + 8 * q, c + 2.8 * q), fill=(211, 47, 47, 255))
    d.rectangle((cx - 2.8 * q, c - 8 * q, cx + 2.8 * q, c + 8 * q), fill=(211, 47, 47, 255))
    out = im.copy()
    out.alpha_composite(shrink(big))
    return out


def egg_sacs(im):
    big = Image.new('RGBA', (im.width * HI, im.height * HI))
    d = ImageDraw.Draw(big)
    c = big.width / 2
    q = HI * im.width / 138
    for (dx, dy, r) in ((-20, -11, 9), (-20, 11, 8.5), (-31, 0, 8), (-10, 0, 6.5), (-29, -15, 5.5), (-29, 15, 5.5)):
        d.ellipse((c + (dx - r) * q, c + (dy - r) * q, c + (dx + r) * q, c + (dy + r) * q), fill=(112, 96, 48, 255))
        d.ellipse((c + (dx - r + 1.8) * q, c + (dy - r + 1.8) * q, c + (dx + r - 1.8) * q, c + (dy + r - 2.6) * q), fill=(226, 212, 146, 255))
        d.ellipse((c + (dx - r * 0.5) * q, c + (dy - r * 0.6) * q, c + (dx - r * 0.1) * q, c + (dy - r * 0.2) * q), fill=(250, 244, 210, 255))
    out = Image.new('RGBA', im.size)
    out.alpha_composite(im)
    out.alpha_composite(shrink(big))
    return out


def aerial(im):
    """A radio pack's aerial trailing behind a character."""
    big = Image.new('RGBA', (im.width * HI, im.height * HI))
    d = ImageDraw.Draw(big)
    c = big.width / 2
    q = HI * im.width / 138
    d.line((c - 14 * q, c - 6 * q, c - 36 * q, c - 22 * q), fill=(50, 54, 60, 255), width=int(2.6 * q))
    d.ellipse((c - 40 * q, c - 26 * q, c - 32 * q, c - 18 * q), fill=(255, 210, 60, 255), outline=(150, 110, 20, 255), width=int(1.4 * q))
    out = im.copy()
    out.alpha_composite(shrink(big))
    return out


# --- soldiers ---------------------------------------------------------------------------
#
# Every tower is a squad in a sandbag pit. A soldier is the shooter pack's soldier
# at the 4x render (256 pixels a cell), dressed in his squad's colour, with the
# weapon of his job drawn in his own frame: (0, 0) is his head and he faces +x.

CELL4 = 256


def trooper_canvas(pose, half=236):
    x, y, w, h = CHARS[pose]
    px, py = x + 66, y + 86
    m = 6
    body = chars_sheet.crop((x - m, y - m, x + w + m, y + h + m))
    im = Image.new('RGBA', (2 * half, 2 * half))
    im.alpha_composite(body, (x - m - (px - half), y - m - (py - half)))
    return im


def uniform(im, colour):
    """The pack soldier's green helmet and uniform into [colour], keeping their light and shade."""
    th, ts, tv = (float(v[0, 0]) for v in rgb_to_hsv(np.array([[colour[:3]]], dtype=np.float32) / 255.0))

    def fn(h, s, v):
        m = greens(h, s)
        return np.where(m, th, h), np.where(m, ts, s), np.where(m, np.clip(tv + (v - 0.55) * 0.9, 0, 1), v)
    return recolor(im, fn)


def ghillie(im, seed=3):
    """A sniper's shaggy camouflage over his body."""
    rng = np.random.default_rng(seed)
    layer = Image.new('RGBA', im.size)
    d = ImageDraw.Draw(layer)
    c = im.width / 2
    for _ in range(46):
        x, y, r = rng.uniform(-74, 24), rng.uniform(-64, 64), rng.uniform(5, 11)
        col = [(66, 82, 44, 235), (112, 122, 70, 235), (92, 76, 50, 235)][rng.integers(3)]
        d.ellipse((c + x - r, c + y - r, c + x + r, c + y + r), fill=col)
    body = np.asarray(im.getchannel('A')) > 100
    keep = np.minimum(np.asarray(layer.getchannel('A')), (body * 255).astype(np.uint8))
    out = im.copy()
    out.paste(layer, (0, 0), Image.fromarray(keep))
    return out


def rr(d, c, x0, y0, x1, y1, fill, w=5, r=6, edge=None):
    """A rounded box with a darker rim, in the soldier's frame."""
    edge = edge or darker(fill)
    d.rounded_rectangle((c + x0, c + y0, c + x1, c + y1), radius=r, fill=edge)
    d.rounded_rectangle((c + x0 + w, c + y0 + w, c + x1 - w, c + y1 - w), radius=max(0, r - w), fill=fill)


def dot(d, c, x, y, r, fill, w=4, edge=None):
    edge = edge or darker(fill)
    d.ellipse((c + x - r, c + y - r, c + x + r, c + y + r), fill=edge)
    d.ellipse((c + x - r + w, c + y - r + w, c + x + r - w, c + y + r - w), fill=fill)


def tri(d, c, pts, fill):
    d.polygon([(c + x, c + y) for (x, y) in pts], fill=fill)


GUN = (52, 56, 62, 255)
GUNMETAL = (96, 102, 110, 255)
WOOD = (150, 98, 56, 255)
OLIVE = (104, 118, 64, 255)
WHITE = (226, 230, 224, 255)
RED = (211, 47, 47, 255)


def trooper(pose, colour, front=None, back=None, camo=False):
    """One soldier at atlas size: [back] is drawn under him, [front] over him."""
    im = uniform(trooper_canvas(pose), colour)
    if camo:
        im = ghillie(im)
    c = im.width / 2
    if back:
        layer = Image.new('RGBA', im.size)
        back(ImageDraw.Draw(layer), c)
        layer.alpha_composite(im)
        im = layer
    if front:
        layer = Image.new('RGBA', im.size)
        front(ImageDraw.Draw(layer), c)
        im.alpha_composite(layer)
    # Square around the head, as small as what shows allows, then down to atlas size.
    l, t, r, b = im.getchannel('A').point(lambda a: 255 if a > 8 else 0).getbbox()
    half = int(max(c - l, r - c, c - t, b - c)) + 6
    im = im.crop((int(c - half), int(c - half), int(c + half), int(c + half)))
    return im.resize((im.width * PX // CELL4, im.height * PX // CELL4), Image.LANCZOS)


# The weapons, each a painter for trooper().

def gatling(d, c):
    for (y, a) in ((0, 0), (0, 38), (0, -38)):
        d.line((c + 96, c + y, c + 96 + 50 * math.cos(math.radians(a)), c + 50 * math.sin(math.radians(a)) * 1.2), fill=GUN, width=8)
    rr(d, c, 20, -17, 98, 17, GUNMETAL)
    for y in (-9, 0, 9):
        rr(d, c, 92, y - 4, 198, y + 4, GUN, w=2, r=3)
    rr(d, c, 182, -15, 194, 15, GUN, w=3, r=3)
    dot(d, c, 50, 32, 17, OLIVE)


def anti_tank(d, c):
    rr(d, c, 16, -11, 82, 11, WOOD)
    rr(d, c, 76, -6, 218, 6, GUN, w=3, r=3)
    rr(d, c, 204, -12, 224, 12, GUN, w=3, r=3)
    d.line((c + 150, c - 6, c + 168, c - 36), fill=GUN, width=6)
    d.line((c + 150, c + 6, c + 168, c + 36), fill=GUN, width=6)


def shotgun(d, c):
    rr(d, c, 18, -13, 72, 13, WOOD)
    rr(d, c, 64, -13, 146, 0, GUN, w=3, r=3)
    rr(d, c, 64, 0, 146, 13, GUN, w=3, r=3)


def auto_shotgun(d, c):
    rr(d, c, 18, -13, 92, 13, GUNMETAL)
    rr(d, c, 86, -7, 156, 7, GUN, w=3, r=3)
    rr(d, c, 146, -10, 160, 10, GUN, w=3, r=3)
    dot(d, c, 58, 26, 20, GUN)
    dot(d, c, 58, 26, 8, GUNMETAL, w=2)


def slug_gun(d, c):
    rr(d, c, 16, -12, 72, 12, WOOD)
    rr(d, c, 66, -9, 184, 9, GUN, w=3, r=4)
    rr(d, c, 172, -12, 188, 12, GUN, w=3, r=3)


def rpg(d, c):
    rr(d, c, -64, 10, 122, 38, OLIVE, r=12)
    tri(d, c, [(120, 6), (170, 24), (120, 42)], (90, 100, 50, 255))
    tri(d, c, [(126, 13), (160, 24), (126, 35)], (126, 140, 72, 255))
    rr(d, c, 28, 36, 50, 54, GUN, w=3, r=3)


def rocket_pod(d, c):
    rr(d, c, -50, 2, 108, 50, (84, 96, 52, 255), r=8)
    for y in (12, 26, 40):
        tri(d, c, [(106, y - 6), (122, y), (106, y + 6)], RED)
    rr(d, c, 30, 48, 50, 62, GUN, w=3, r=3)


def atgm(d, c):
    rr(d, c, -76, 6, 150, 44, (80, 90, 60, 255), r=14)
    tri(d, c, [(148, 4), (196, 25), (148, 46)], (88, 98, 70, 255))
    tri(d, c, [(156, 14), (186, 25), (156, 36)], RED)
    rr(d, c, 20, -18, 62, 8, GUN, w=3, r=4)


def flame_tanks(colour, stripes=False, frost=False):
    def back(d, c):
        for y in (-26, 26):
            rr(d, c, -98, y - 22, -20, y + 22, colour, r=20)
            if stripes:
                for x in (-86, -66, -46):
                    tri(d, c, [(x, y - 18), (x + 8, y - 18), (x + 2, y + 18), (x - 6, y + 18)], (40, 34, 30, 255))
            d.rounded_rectangle((c - 88, c + y - 14, c - 40, c + y - 7), radius=4, fill=lighter(colour, 0.5))
            if frost:
                for x in (-76, -50):
                    d.line((c + x - 7, c + y + 6, c + x + 7, c + y + 6), fill=(255, 255, 255, 255), width=4)
                    d.line((c + x, c + y - 1, c + x, c + y + 13), fill=(255, 255, 255, 255), width=4)
    return back


def flame_gun(big=False, frost=False):
    def front(d, c):
        w = 11 if big else 9
        rr(d, c, 22, -w, 136, w, GUNMETAL, w=3, r=4)
        rr(d, c, 124, -w - 6, 146, w + 6, GUN, w=3, r=4)
        d.ellipse((c + 144, c - 10, c + 164, c + 10), fill=(190, 240, 255, 255) if frost else (255, 190, 80, 255))
    return front


def manpads(d, c):
    rr(d, c, -72, 8, 142, 34, (214, 218, 204, 255), r=12, edge=(92, 98, 82, 255))
    tri(d, c, [(140, 8), (170, 21), (140, 34)], RED)
    rr(d, c, 40, 32, 60, 52, GUN, w=3, r=3)


def salvo_pack(d, c):
    for (y, x0) in ((4, -60), (22, -66), (40, -60)):
        rr(d, c, x0, y - 9, x0 + 190, y + 9, (214, 218, 204, 255), w=4, r=8, edge=(92, 98, 82, 255))
        tri(d, c, [(x0 + 188, y - 8), (x0 + 210, y), (x0 + 188, y + 8)], RED)


def longbow_tube(d, c):
    rr(d, c, -84, 4, 168, 44, (176, 182, 168, 255), r=16, edge=(86, 92, 78, 255))
    tri(d, c, [(166, 4), (212, 24), (166, 44)], RED)
    rr(d, c, 24, -20, 64, 8, GUN, w=3, r=4)


def tesla_gun(core, rings=2, big=False):
    def front(d, c):
        rr(d, c, 18, -12, 92, 12, GUN)
        for k in range(rings):
            x = 100 + k * 18
            r = 22 if big else 18
            d.ellipse((c + x - 7, c - r, c + x + 7, c + r), outline=(201, 125, 58, 255), width=6)
        x = 104 + rings * 18
        d.ellipse((c + x - 18, c - 18, c + x + 18, c + 18), fill=lighter(core, 0.15))
        d.ellipse((c + x - 11, c - 11, c + x + 11, c + 11), fill=core)
        d.ellipse((c + x - 9, c - 9, c + x - 1, c - 1), fill=(255, 255, 255, 230))
    return front


def sniper_rifle(thick=False, laser=False):
    def front(d, c):
        rr(d, c, 18, -10, 76, 10, (84, 98, 58, 255))
        w = 8 if thick else 5
        rr(d, c, 70, -w, 230, w, GUN, w=2, r=3)
        if thick:
            rr(d, c, 214, -13, 236, 13, GUN, w=3, r=3)
            d.line((c + 170, c - 6, c + 186, c - 34), fill=GUN, width=6)
            d.line((c + 170, c + 6, c + 186, c + 34), fill=GUN, width=6)
        rr(d, c, 40, -26, 98, -13, GUN, w=3, r=4)
        d.ellipse((c + 92, c - 25, c + 104, c - 13), fill=(127, 211, 255, 255))
        if laser:
            d.line((c + 100, c - 19, c + 250, c - 19), fill=(255, 60, 60, 190), width=4)
            d.ellipse((c + 94, c - 25, c + 106, c - 13), fill=(255, 70, 60, 255))
    return front


def radio_pack(d, c):
    rr(d, c, -98, -30, -30, 30, (86, 92, 70, 255), r=8)
    d.line((c - 82, c - 20, c - 136, c - 84), fill=GUN, width=5)
    d.ellipse((c - 144, c - 92, c - 128, c - 76), fill=(255, 70, 60, 255))


def handset(d, c):
    rr(d, c, 30, -42, 54, -18, GUN, w=3, r=4)


def binoculars(d, c):
    for y in (-11, 11):
        rr(d, c, 36, y - 8, 76, y + 8, GUN, w=3, r=5)
    d.ellipse((c + 70, c - 18, c + 80, c - 4), fill=(127, 211, 255, 255))
    d.ellipse((c + 70, c + 4, c + 80, c + 18), fill=(127, 211, 255, 255))


def jam_pack(d, c):
    rr(d, c, -104, -40, -26, 40, (60, 120, 112, 255), r=10)
    for r in (34, 50):
        d.arc((c - 64 - r, c - r, c - 64 + r, c + r), 140, 220, fill=(120, 200, 190, 255), width=6)
    dot(d, c, -64, 0, 10, (255, 70, 60, 255), w=3)


def detector(d, c):
    d.line((c + 30, c + 6, c + 150, c + 6), fill=GUN, width=7)
    d.ellipse((c + 140, c - 22, c + 196, c + 34), outline=(150, 156, 164, 255), width=8)
    rr(d, c, 30, 14, 60, 34, (70, 76, 82, 255), w=3, r=4)


def plunger(d, c):
    rr(d, c, 40, -26, 92, 26, (126, 92, 52, 255))
    d.line((c + 66, c - 26, c + 66, c - 44), fill=GUN, width=7)
    rr(d, c, 50, -52, 82, -40, GUN, w=3, r=3)
    d.line((c + 92, c, c + 150, c + 20), fill=(200, 60, 50, 255), width=4)


def wire_coil(d, c):
    for r, w in ((30, 7), (20, 5)):
        d.ellipse((c + 74 - r, c - r, c + 74 + r, c + r), outline=(150, 156, 164, 255), width=w)
    for a in range(0, 360, 30):
        x = c + 74 + math.cos(math.radians(a)) * 30
        y = c + math.sin(math.radians(a)) * 30
        d.line((x - 5, y - 5, x + 5, y + 5), fill=(110, 114, 120, 255), width=3)


def crate(d, c):
    rr(d, c, 30, -34, 98, 34, WOOD, r=4)
    d.line((c + 36, c - 28, c + 92, c + 28), fill=darker(WOOD, 0.75), width=6)
    d.line((c + 36, c + 28, c + 92, c - 28), fill=darker(WOOD, 0.75), width=6)


def jackhammer(d, c):
    rr(d, c, 30, -36, 48, 36, GUN, w=3, r=6)
    rr(d, c, 40, -10, 118, 10, GUNMETAL, w=3, r=4)
    tri(d, c, [(116, -6), (140, 0), (116, 6)], (176, 186, 196, 255))
    d.ellipse((c + 8, c - 8, c + 24, c + 8), fill=(255, 230, 120, 255))


def money_bag(d, c):
    dot(d, c, 64, 0, 28, (206, 176, 112, 255), w=4)
    rr(d, c, 30, -8, 44, 8, (150, 120, 70, 255), w=3, r=3)
    dot(d, c, 68, 0, 12, GOLD, w=3, edge=(176, 120, 20, 255))


def vest(im):
    """An enemy sapper's yellow hazard vest over the survivor's blue shirt."""
    def fn(h, s, v):
        m = (h > 0.55) & (h < 0.72) & (s > 0.15)
        return np.where(m, 0.13, h), np.where(m, 0.8, s), np.where(m, np.clip(v * 1.5, 0, 0.95), v)
    return recolor(im, fn)


# --- the squads' pits and props ------------------------------------------------------------

PIT_FLOOR = {'green': (126, 94, 60, 255), 'desert': (194, 158, 108, 255), 'winter': (170, 162, 156, 255)}


def pit(biome, elite):
    """A dug-in position ringed with sandbags; an elite squad's bags are darker."""
    n = int(PX * 1.1)
    im = Image.new('RGBA', (n, n))
    d = ImageDraw.Draw(im)
    c = n / 2
    r = 0.37 * PX
    floor = PIT_FLOOR[biome]
    d.ellipse((c - r - 3, c - r - 3, c + r + 3, c + r + 3), fill=darker(floor, 0.75))
    d.ellipse((c - r + 4, c - r + 4, c + r, c + r), fill=floor)
    bag = tanks('sandbagBrown' if elite else 'sandbagBeige')
    count = 10
    for i in range(count):
        a = i * 360 / count + 9
        b = scale(bag.rotate(-a - 90, expand=True, resample=Image.BICUBIC), 0.58)
        x = c + math.cos(math.radians(a)) * 0.43 * PX - b.width / 2
        y = c + math.sin(math.radians(a)) * 0.43 * PX - b.height / 2
        im.alpha_composite(b, (int(x), int(y)))
    if biome == 'winter':
        im = snow_on(im, -3, -4)
    return shadowed(im, 2, 3, 2, 0.3)


def map_table():
    im, d, c = canvas(0.6)
    part(d, 'round', box(c, -26, -18, 26, 18), (150, 112, 70, 255), width=3, radius=3)
    d.rectangle(box(c, -20, -13, 20, 13), fill=(232, 222, 190, 255))
    for (x0, y0, x1, y1) in ((-16, -6, 4, 8), (4, 8, 16, -4), (-14, 6, -4, -10)):
        d.line(box(c, x0, y0, x1, y1), fill=(120, 150, 110, 255), width=int(1.6 * U))
    disc(d, c, 6, -4, 3, (220, 50, 40, 255), width=1)
    return shrink(im)


def bunker():
    """The base: a metal crate ringed with sandbags."""
    s = PX
    im = Image.new('RGBA', (s, s))
    bag = tanks('sandbagBrown')
    for k in range(8):
        a = k * 45
        b = bag.rotate(-a - 90, expand=True, resample=Image.BICUBIC)
        b = scale(b, 0.62)
        x = s / 2 + math.cos(math.radians(a)) * s * 0.36 - b.width / 2
        y = s / 2 + math.sin(math.radians(a)) * s * 0.36 - b.height / 2
        im.alpha_composite(b, (int(x), int(y)))
    crate = scale(tanks('crateMetal'), 1.1)
    im.alpha_composite(crate, ((s - crate.width) // 2, (s - crate.height) // 2))
    return im


# --- the sprites ------------------------------------------------------------------------

S = {}

# Ground and roads, opaque cell squares.
S['ground.green.0'] = tanks('tileGrass1').resize((PX, PX), Image.LANCZOS)
S['ground.green.1'] = tanks('tileGrass2').resize((PX, PX), Image.LANCZOS)
S['ground.desert.0'] = tanks('tileSand1').resize((PX, PX), Image.LANCZOS)
S['ground.desert.1'] = tanks('tileSand2').resize((PX, PX), Image.LANCZOS)
S['ground.winter.0'] = snow(S['ground.green.0'])
S['ground.winter.1'] = snow(S['ground.green.1'])

N, E, SO, WE = 1, 2, 4, 8


def road_mask(im, ground):
    """Which edges of a road tile the road touches, from colours near the edge midpoints."""
    a = np.asarray(im.convert('RGB')).astype(float)
    g = np.asarray(ground.convert('RGB')).astype(float).reshape(-1, 3).mean(axis=0)
    n = a.shape[0]
    mask = 0
    for bit, (y, x) in ((N, (3, n // 2)), (E, (n // 2, n - 4)), (SO, (n - 4, n // 2)), (WE, (n // 2, 3))):
        patch = a[max(0, y - 3):y + 4, max(0, x - 3):x + 4].reshape(-1, 3).mean(axis=0)
        if np.abs(patch - g).mean() > 28:
            mask |= bit
    return mask


ROAD_TILES = ['roadEast', 'roadNorth', 'roadCornerLL', 'roadCornerLR', 'roadCornerUL', 'roadCornerUR',
              'roadSplitE', 'roadSplitN', 'roadSplitS', 'roadSplitW', 'roadCrossing']
for biome, prefix, ground, change in (('green', 'tileGrass_', 'tileGrass1', None), ('desert', 'tileSand_', 'tileSand1', None),
                                      ('winter', 'tileGrass_', 'tileGrass1', snow)):
    found = {}
    for t in ROAD_TILES:
        im = tanks(prefix + t).resize((PX, PX), Image.LANCZOS)
        m = road_mask(im, tanks(ground).resize((PX, PX), Image.LANCZOS))
        found[m] = change(im) if change else im
    # A road that ends (at the base or the map edge) uses the straight piece.
    for m in (N, SO):
        found.setdefault(m, found[N | SO])
    for m in (E, WE):
        found.setdefault(m, found[E | WE])
    for m, im in found.items():
        S['road.%s.%d' % (biome, m)] = im
    missing = [m for m in range(1, 16) if m not in found]
    if missing:
        sys.exit('no road tile for masks %s in %s' % (missing, biome))

# Obstacles and decoration, with their shadows.
S['tree.green.0'] = shadowed(trim(td(133)))
S['tree.green.1'] = shadowed(trim(tanks('treeGreen_large')))
S['tree.green.2'] = shadowed(trim(td(130)))
S['tree.desert.0'] = shadowed(trim(td(134)))
S['tree.desert.1'] = shadowed(trim(scale(td(132), 1.1)))
S['tree.winter.0'] = shadowed(trim(snow_on(pine(tanks('treeGreen_large')), blobs=5)))
S['tree.winter.1'] = shadowed(trim(snow_on(pine(td(130)), blobs=4)))
S['rock.green.0'] = shadowed(trim(td(136)))
S['rock.green.1'] = shadowed(trim(td(135)))
S['rock.desert.0'] = shadowed(trim(sandstone(td(136))))
S['rock.desert.1'] = shadowed(trim(sandstone(td(137))))
S['rock.winter.0'] = shadowed(trim(snowcap(td(136))))
S['rock.winter.1'] = shadowed(trim(snowcap(td(135))))
S['cactus'] = shadowed(cactus())
S['base'] = shadowed(bunker(), alpha=0.35)
S['scorch'] = trim(td(19))
S['debris'] = trim(td(20))
S['oil'] = trim(tanks('oilSpill_large'))
for k, n in enumerate((295, 296, 298)):
    S['fire.%d' % k] = trim(td(n))
for k in range(5):
    S['blast.%d' % k] = trim(tanks('explosion%d' % (k + 1)))
    S['smoke.%d' % k] = trim(tanks('explosionSmoke%d' % (k + 1)))
S['coin'] = trim(td(272))
S['flash'] = trim(face_right_from_up(tanks('shotOrange')))
S['flash.big'] = trim(face_right_from_up(tanks('shotLarge')))

# Squads. Each tower type has its uniform, each job its weapon; a specialisation
# puts one special soldier at the front of the squad.
TEAL = rgba(0x2F9C8F)
SQUAD = {
    'turret': rgba(0x4E8C3A), 'shotgun': rgba(0xC9A060), 'rocket': rgba(0x9A3A36), 'flamer': rgba(0x43474E),
    'sam': rgba(0x5D9BD6), 'tesla': rgba(0x8A5CC8), 'sniper': rgba(0x55643A), 'mines': rgba(0xE07B2E),
    'radar': TEAL, 'quarry': rgba(0x3F4F8C),
}
S['trooper.turret'] = trooper('soldier1_machine', SQUAD['turret'])
S['trooper.minigun'] = trooper('soldier1_hold', SQUAD['turret'], gatling)
S['trooper.piercer'] = trooper('soldier1_hold', SQUAD['turret'], anti_tank)
S['trooper.shotgun'] = trooper('soldier1_hold', SQUAD['shotgun'], shotgun)
S['trooper.assault'] = trooper('soldier1_hold', SQUAD['shotgun'], auto_shotgun)
S['trooper.slug'] = trooper('soldier1_hold', SQUAD['shotgun'], slug_gun)
S['trooper.rocket'] = trooper('soldier1_hold', SQUAD['rocket'], rpg)
S['trooper.cluster'] = trooper('soldier1_hold', SQUAD['rocket'], rocket_pod)
S['trooper.heavy'] = trooper('soldier1_hold', SQUAD['rocket'], atgm)
S['trooper.flamer'] = trooper('soldier1_hold', SQUAD['flamer'], flame_gun(), flame_tanks(rgba(0xE0592B)))
S['trooper.napalm'] = trooper('soldier1_hold', SQUAD['flamer'], flame_gun(big=True), flame_tanks(rgba(0xF0B020), stripes=True))
S['trooper.cryo'] = trooper('soldier1_hold', rgba(0xE6EAEE), flame_gun(frost=True), flame_tanks(rgba(0x8FD3F5), frost=True))
S['trooper.sam'] = trooper('soldier1_hold', SQUAD['sam'], manpads)
S['trooper.salvo'] = trooper('soldier1_hold', SQUAD['sam'], salvo_pack)
S['trooper.longbow'] = trooper('soldier1_hold', SQUAD['sam'], longbow_tube)
S['trooper.tesla'] = trooper('soldier1_hold', SQUAD['tesla'], tesla_gun((111, 227, 255, 255)))
S['trooper.storm'] = trooper('soldier1_hold', SQUAD['tesla'], tesla_gun((170, 210, 255, 255), rings=3, big=True))
S['trooper.stun'] = trooper('soldier1_hold', SQUAD['tesla'], tesla_gun((255, 227, 107, 255)))
S['trooper.sniper'] = trooper('soldier1_hold', SQUAD['sniper'], sniper_rifle(), camo=True)
S['trooper.heavycal'] = trooper('soldier1_hold', SQUAD['sniper'], sniper_rifle(thick=True), camo=True)
S['trooper.marker'] = trooper('soldier1_hold', SQUAD['sniper'], sniper_rifle(laser=True), camo=True)
S['trooper.mines'] = trooper('soldier1_hold', SQUAD['mines'], detector)
S['trooper.landmine'] = trooper('soldier1_hold', SQUAD['mines'], plunger)
S['trooper.spikes'] = trooper('soldier1_hold', SQUAD['mines'], wire_coil)
S['trooper.radar'] = trooper('soldier1_hold', SQUAD['radar'], handset, radio_pack)
S['trooper.command'] = trooper('soldier1_hold', SQUAD['radar'], binoculars, radio_pack)
S['trooper.jammer'] = trooper('soldier1_hold', SQUAD['radar'], handset, jam_pack)
S['trooper.quarry'] = trooper('soldier1_hold', SQUAD['quarry'], crate)
S['trooper.drill'] = trooper('soldier1_hold', SQUAD['quarry'], jackhammer)
S['trooper.bank'] = trooper('soldier1_hold', SQUAD['quarry'], money_bag)
for biome in ('green', 'desert', 'winter'):
    S['pit.%s' % biome] = pit(biome, False)
    S['pit.%s.elite' % biome] = pit(biome, True)
S['prop.dish'] = scale(radar_dish(), 0.7)
S['prop.table'] = map_table()
S['prop.jammer'] = scale(jammer(), 0.62)
S['mine'] = shadowed(scale(tanks('barrelBlack_top'), 0.78), 2, 3, 2, 0.35)
S['mine.big'] = shadowed(scale(tanks('barrelGreen_top'), 1.1), 2, 3, 2, 0.35)
S['spikes'] = shadowed(trim(tanks('barricadeMetal')), 2, 3, 2, 0.35)
S['quarry'] = mine_cart()
S['quarry.drill'] = derrick()
S['quarry.bit'] = drill_bit()
S['quarry.bank'] = scale(tanks('crateMetal'), 1.45)
S['quarry.dial'] = vault_dial()

# Enemies, facing right and centred on the body.
S['enemy.bandit'] = character('manBrown_gun')
S['enemy.runner'] = character('womanGreen_stand')
S['enemy.swarm'] = character('zoimbie1_hold')
S['enemy.brute'] = character('robot1_machine')
S['enemy.medic'] = red_cross(white_coat(character('manOld_stand')))
S['enemy.shield'] = riot_shield(character('manBlue_hold'))
S['enemy.ghost'] = ghostly(character('hitman1_silencer'))
S['enemy.sapper'] = aerial(vest(character('survivor1_stand')))
S['enemy.mother'] = egg_sacs(hue(character('zoimbie1_stand', 1.45), 0.13, sat=0.5, light=0.85))
S['enemy.drone'] = trim(td(271))
S['shadow.drone'] = trim(td(294))
S['enemy.apc'] = square(face_right_from_down(tanks('tank_huge')))
S['enemy.heli'] = heli_body()
S['plane'] = trim(td(270))
S['shadow.plane'] = trim(td(293))
S['rocket'] = trim(face_right_from_up(td(251)))
S['rocket.big'] = trim(face_right_from_up(td(252)))


# --- interface icons ---------------------------------------------------------------------

# White pictures the game paints in any colour, on 100 px squares with the shape
# about 64 px across, like Kenney's Game Icons. A few more are drawn here in that style.
ICON = 100
GAME_ICONS = K / 'gameicons/PNG/White/2x'
MORE_ICONS = K / 'gameicons-expansion/PNG/White/2x'


def kicon(name, folder=GAME_ICONS):
    return Image.open(folder / (name + '.png')).convert('RGBA')


def glyph(paint):
    """[paint] draws white on a large mask, black cuts holes; returns the shrunk icon."""
    n = ICON * HI
    m = Image.new('L', (n, n), 0)
    paint(ImageDraw.Draw(m), n / 100.0)
    im = Image.new('RGBA', (ICON, ICON), (255, 255, 255, 0))
    im.putalpha(m.resize((ICON, ICON), Image.LANCZOS))
    return im


def ib(k, l, t, r, b):
    """A box on the 100-unit icon square, [k] mask pixels per unit."""
    return (l * k, t * k, r * k, b * k)


def heart_icon(d, k):
    d.ellipse(ib(k, 17, 22, 53, 58), fill=255)
    d.ellipse(ib(k, 47, 22, 83, 58), fill=255)
    d.polygon([(19 * k, 47 * k), (81 * k, 47 * k), (50 * k, 82 * k)], fill=255)
    d.rectangle(ib(k, 35, 34, 65, 55), fill=255)


def skull_icon(d, k):
    d.ellipse(ib(k, 20, 16, 80, 70), fill=255)
    d.rounded_rectangle(ib(k, 32, 54, 68, 84), 6 * k, fill=255)
    d.ellipse(ib(k, 29, 38, 47, 56), fill=0)
    d.ellipse(ib(k, 53, 38, 71, 56), fill=0)
    d.polygon([(50 * k, 57 * k), (45 * k, 66 * k), (55 * k, 66 * k)], fill=0)
    for x in (41, 50, 59):
        d.rectangle(ib(k, x - 1.6, 73, x + 1.6, 84), fill=0)


def book_icon(d, k):
    # An open book seen from above: two pages and a spine.
    d.polygon([(16 * k, 24 * k), (47 * k, 30 * k), (47 * k, 82 * k), (16 * k, 76 * k)], fill=255)
    d.polygon([(84 * k, 24 * k), (53 * k, 30 * k), (53 * k, 82 * k), (84 * k, 76 * k)], fill=255)
    for y in (40, 51, 62):
        d.line([(23 * k, (y - 4) * k), (40 * k, (y - 1) * k)], fill=0, width=int(4 * k))
        d.line([(77 * k, (y - 4) * k), (60 * k, (y - 1) * k)], fill=0, width=int(4 * k))


def calendar_icon(d, k):
    d.rounded_rectangle(ib(k, 18, 24, 82, 84), 7 * k, fill=255)
    d.rectangle(ib(k, 18, 38, 82, 42), fill=0)
    for x in (34, 66):
        d.rounded_rectangle(ib(k, x - 4, 14, x + 4, 31), 3 * k, fill=255)
    for row in range(2):
        for col in range(3):
            x, y = 27 + col * 17, 50 + row * 15
            d.rectangle(ib(k, x, y, x + 12, y + 10), fill=0)
    d.rectangle(ib(k, 27, 50, 39, 60), fill=255)


def infinity_icon(d, k):
    # A lemniscate, traced with a round brush.
    r = 5.5 * k
    for i in range(720):
        t = 2 * math.pi * i / 720
        q = 1 + math.sin(t) ** 2
        x, y = (50 + 36 * math.cos(t) / q) * k, (50 + 36 * math.sin(t) * math.cos(t) / q) * k
        d.ellipse((x - r, y - r, x + r, y + r), fill=255)


def ranks_icon(d, k):
    # Three sergeant's chevrons pointing up.
    for i in range(3):
        y = 24 + i * 19
        d.polygon([(50 * k, y * k), (84 * k, (y + 20) * k), (84 * k, (y + 31) * k), (50 * k, (y + 11) * k),
                   (16 * k, (y + 31) * k), (16 * k, (y + 20) * k)], fill=255)


for name, src in {
    'back': 'arrowLeft', 'gear': 'gear', 'trophy': 'trophy', 'play': 'right', 'pause': 'pause', 'cross': 'cross',
    'undo': 'return', 'lock': 'locked', 'star': 'star', 'medal': 'medal1', 'speed': 'fastForward',
    'target': 'target', 'sound': 'audioOn', 'music': 'musicOn', 'warning': 'warning', 'home': 'home',
    'check': 'checkmark', 'info': 'information',
}.items():
    S['icon.' + name] = kicon(src)
S['icon.flag'] = kicon('flag', MORE_ICONS)
S['icon.heart'] = glyph(heart_icon)
S['icon.skull'] = glyph(skull_icon)
S['icon.book'] = glyph(book_icon)
S['icon.calendar'] = glyph(calendar_icon)
S['icon.infinity'] = glyph(infinity_icon)
S['icon.ranks'] = glyph(ranks_icon)


# --- packing -----------------------------------------------------------------------------

def extrude(im, pad):
    """Repeats the edge pixels into the padding so filtering never pulls in a neighbour."""
    w, h = im.size
    out = Image.new('RGBA', (w + 2 * pad, h + 2 * pad))
    out.alpha_composite(im, (pad, pad))
    a = np.asarray(out).copy()
    a[:pad, pad:pad + w] = a[pad:pad + 1, pad:pad + w]
    a[pad + h:, pad:pad + w] = a[pad + h - 1:pad + h, pad:pad + w]
    a[:, :pad] = a[:, pad:pad + 1]
    a[:, pad + w:] = a[:, pad + w - 1:pad + w]
    return Image.fromarray(a, 'RGBA')


PAD = 2
items = sorted(S.items(), key=lambda kv: (-kv[1].height, -kv[1].width, kv[0]))
width = 2048
x = y = shelf = 0
places = {}
for name, im in items:
    w, h = im.width + 2 * PAD, im.height + 2 * PAD
    if x + w > width:
        x, y, shelf = 0, y + shelf, 0
    places[name] = (x, y)
    x += w
    shelf = max(shelf, h)
height = (y + shelf + 3) // 4 * 4
if height > 2048:
    sys.exit('atlas too tall: %d' % height)
atlas = Image.new('RGBA', (width, height))
lines = []
for name, im in items:
    px, py = places[name]
    atlas.alpha_composite(extrude(im, PAD), (px, py))
    l, t, r, b = im.getchannel('A').point(lambda a: 255 if a > 40 else 0).getbbox() or (0, 0, im.width, im.height)
    lines.append('%s %d %d %d %d %d %d %d %d' % (name, px + PAD, py + PAD, im.width, im.height, l, t, r - l, b - t))
atlas.save(OUT / 'atlas.png', optimize=True)
(OUT / 'atlas.txt').write_text(
    '# name x y w h, then the part that shows (x y w h inside it); %d atlas pixels per map cell\n' % PX
    + '\n'.join(sorted(lines)) + '\n')
print('atlas %dx%d, %d sprites, %.0f KB' % (width, height, len(S), (OUT / 'atlas.png').stat().st_size / 1024))

if args.preview:
    # Every sprite at its real size on a grass-coloured sheet, a cell grid behind it.
    cell = 160
    cols = 10
    names = sorted(S)
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cell, rows * (cell + 14)), (88, 128, 88, 255))
    d = ImageDraw.Draw(sheet)
    for i, name in enumerate(names):
        im = S[name].copy()
        im.thumbnail((cell - 4, cell - 4))
        cx, cy = (i % cols) * cell, (i // cols) * (cell + 14)
        g = (cell - PX) // 2
        d.rectangle((cx + g, cy + g, cx + g + PX, cy + g + PX), outline=(110, 150, 110, 255))
        sheet.alpha_composite(im, (cx + (cell - im.width) // 2, cy + (cell - im.height) // 2))
        d.text((cx + 3, cy + cell), name, fill=(255, 255, 255, 255))
    sheet.convert('RGB').save(args.preview)
