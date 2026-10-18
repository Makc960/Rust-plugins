#!/usr/bin/env python3
"""
Builds the game's sprite atlas from Kenney's CC0 asset packs (kenney.nl).

The vector sheets of "Tower Defense (top-down)" and "Top-down Shooter" are
rendered at 4x with headless Chromium (render_svg.js), every named sprite is
found in those renders, turned to face right, recoloured where a world needs
it (snow, sandstone, a purple ghost...) and packed with a few pieces drawn
here in the same flat style. Output: engine/src/main/resources/sprites/
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


def over(base, top, dx=0, dy=0):
    """[top] centred on [base], moved by (dx, dy) atlas pixels."""
    out = base.copy()
    out.alpha_composite(top, (int(round((base.width - top.width) / 2 + dx)), int(round((base.height - top.height) / 2 + dy))))
    return out


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


def metal(im, target, sat=0.28):
    """Tints the grey metal parts (and only those) towards [target]."""
    def fn(h, s, v):
        m = (s < 0.15) & (v > 0.25) & (v < 0.95)
        return np.where(m, target, h), np.where(m, sat, s), v
    return recolor(im, fn)


def tint(im, h, s=1.0, v=1.0):
    """Coloured pixels to hue [h], their saturation and value scaled; greys stay."""
    def fn(hh, ss, vv):
        m = ss > 0.18
        return np.where(m, h, hh), np.where(m, ss * s, ss), np.where(m, vv * v, vv)
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


def hazard(im):
    """The soldier's green into a sapper's warning yellow."""
    def fn(h, s, v):
        m = greens(h, s)
        return np.where(m, 0.12, h), np.where(m, 0.78, s), np.where(m, np.clip(v * 1.45, 0, 0.95), v)
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


def barrel(d, c, x0, x1, y, w, color=STEEL, tip=0):
    """A gun barrel along +x from x0 to x1, [w] thick, with an optional wider muzzle."""
    part(d, 'round', box(c, x0, y - w / 2, x1, y + w / 2), color, width=2.5, radius=2)
    if tip:
        part(d, 'round', box(c, x1 - 9, y - w / 2 - tip, x1 + 1, y + w / 2 + tip), color, width=2.5, radius=2)


def tesla(core, rings=2, spikes=0):
    im, d, c = canvas()
    disc(d, c, 0, 0, 37, (58, 64, 72, 255), width=5)
    for k in range(rings):
        r = 30 - k * 8.5
        d.ellipse(box(c, -r, -r, r, r), outline=(201, 125, 58, 255), width=int(3.4 * U))
    for k in range(spikes):
        a = math.radians(k * 360 / spikes + 22)
        x, y = math.cos(a) * 33, math.sin(a) * 33
        disc(d, c, x, y, 4.5, (201, 125, 58, 255), width=1.5)
    disc(d, c, 0, 0, 9, core, lighter(core, 0.5), width=2)
    d.ellipse(box(c, -6, -6, -1, -1), fill=(255, 255, 255, 230))
    return shrink(im)


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


def command_tent():
    im, d, c = canvas()
    part(d, 'round', box(c, -36, -30, 36, 30), (98, 120, 62, 255), width=4, radius=5)
    d.polygon([box(c, -32, -26, 0, 0)[:2], box(c, 0, 0, 0, 0)[:2], box(c, -32, 26, 0, 0)[:2]], fill=(84, 104, 52, 255))
    d.polygon([box(c, 32, -26, 0, 0)[:2], box(c, 0, 0, 0, 0)[:2], box(c, 32, 26, 0, 0)[:2]], fill=(112, 136, 72, 255))
    d.line(box(c, -32, 0, 32, 0), fill=(70, 88, 44, 255), width=int(3 * U))
    # A radio box with an aerial by the door.
    part(d, 'round', box(c, 16, 14, 30, 26), (70, 76, 82, 255), width=2, radius=2)
    disc(d, c, 26, 18, 2.2, (110, 230, 110, 255), width=0.8)
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


def flamer_head(hull, tank, big=False, frost=False, stripes=False):
    """A turret hull with two fuel tanks on its back and a nozzle pointing right."""
    im, d, c = canvas()
    length = 48 if big else 44
    barrel(d, c, 0, length, 0, 11 if big else 9, STEEL, tip=3)
    out = shrink(im)
    out.alpha_composite(hull)
    im, d, c = canvas()
    for y in (-14, 14):
        part(d, 'round', box(c, -40, y - 9, -8, y + 9), tank, width=3, radius=8)
        if stripes:
            for x in (-33, -24, -15):
                d.polygon([box(c, x, y - 7, 0, 0)[:2], box(c, x + 4, y - 7, 0, 0)[:2],
                           box(c, x + 1, y + 7, 0, 0)[:2], box(c, x - 3, y + 7, 0, 0)[:2]], fill=(40, 34, 30, 255))
        d.rounded_rectangle(box(c, -35, y - 6, -14, y - 2), radius=2 * U, fill=lighter(tank, 0.45))
        if frost:
            for x in (-30, -18):
                d.line(box(c, x - 3, y + 2, x + 3, y + 2), fill=(255, 255, 255, 255), width=int(1.4 * U))
                d.line(box(c, x, y - 1, x, y + 5), fill=(255, 255, 255, 255), width=int(1.4 * U))
    disc(d, c, length + 2, 0, 3, (190, 240, 255, 255) if frost else (255, 190, 80, 255), width=1)
    out.alpha_composite(shrink(im))
    return out


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


def body_only(im):
    """Cuts the grey barrels off a right-facing pack weapon: everything past its coloured hull."""
    a = np.asarray(im).astype(np.float32) / 255.0
    _, sat, _ = rgb_to_hsv(a[..., :3])
    hull = (sat > 0.25) & (a[..., 3] > 0.4)
    arr = np.asarray(im).copy()
    arr[:, np.nonzero(hull.any(axis=0))[0].max() + 1:] = 0
    return Image.fromarray(arr, 'RGBA')


def centre_head(im):
    """Puts the body of a right-facing head (its tall middle, not the barrel) in the middle of a square."""
    a = np.asarray(im.getchannel('A')) > 100
    heights = a.sum(axis=0)
    tall = np.nonzero(heights > heights.max() * 0.45)[0]
    rows = np.nonzero(a.any(axis=1))[0]
    cx = (tall.min() + tall.max() + 1) / 2
    cy = (rows.min() + rows.max() + 1) / 2
    half = int(math.ceil(max(cx, im.width - cx, cy, im.height - cy))) + 1
    out = Image.new('RGBA', (2 * half, 2 * half))
    out.alpha_composite(im, (int(round(half - cx)), int(round(half - cy))))
    return out


def gun_head(base, barrels, back=None):
    """A pack weapon with extra barrels drawn under it: [barrels] is (y, length, width, color, tip)."""
    im, d, c = canvas()
    for (y, length, w, color, tip) in barrels:
        barrel(d, c, -6, length, y, w, color, tip)
    if back:
        back(d, c)
    out = shrink(im)
    out.alpha_composite(base)
    return out


def missiles_on(base, n, big_one=False):
    """A launcher pad with [n] missiles lying on it, pointing right."""
    out = base.copy()
    m = face_right_from_up(td(252 if big_one else 251))
    m = trim(m)
    if not big_one:
        m = scale(m, 0.95)
    step = out.height * (0.2 if n == 3 else 0.24)
    for k in range(n):
        y = int(out.height / 2 - m.height / 2 + (k - (n - 1) / 2) * step)
        out.alpha_composite(m, (int(out.width / 2 - m.width / 2 + out.width * 0.06), y))
    return out


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

# Tower plates (still) and heads (turning, facing right, turning about their middle).
S['plate'] = shadowed(trim(td(181)), alpha=0.35)
S['plate.elite'] = shadowed(trim(td(182)), alpha=0.35)
turret = face_right_from_up(td(249))
S['head.turret'] = tint(turret, 0.2, 0.75, 0.72)
S['head.minigun'] = face_right_from_up(td(250))
S['head.piercer'] = face_right_from_up(td(226))

# The shotguns and snipers: a pack body with barrels of their own.
hull = body_only(turret)
S['head.shotgun'] = gun_head(tint(hull, 0.08, 0.9, 0.85), [(-8, 46, 12, STEEL, 2), (8, 46, 12, STEEL, 2)])
S['head.assault'] = gun_head(tint(hull, 0.13, 0.6, 0.62), [(-13, 44, 10, STEEL, 2), (0, 48, 10, STEEL, 2), (13, 44, 10, STEEL, 2)])
S['head.slug'] = gun_head(tint(hull, 0.6, 0.25, 0.62), [(0, 54, 16, (100, 106, 114, 255), 3)])

S['head.rocket'] = face_right_from_up(td(205))
S['head.cluster'] = face_right_from_up(td(204))
S['head.heavy'] = face_right_from_up(td(206))
S['head.flamer'] = flamer_head(tint(hull, 0.03, 0.55, 0.62), rgba(0xE0592B))
S['head.napalm'] = flamer_head(tint(hull, 0.07, 0.6, 0.5), rgba(0xF0A01C), big=True, stripes=True)
S['head.cryo'] = flamer_head(tint(hull, 0.58, 0.45, 0.75), rgba(0x8FD3F5), frost=True)
S['head.sam'] = missiles_on(face_right_from_up(metal(td(203), 0.6)), 2)
S['head.salvo'] = missiles_on(face_right_from_up(metal(td(228), 0.6)), 3)
S['head.longbow'] = missiles_on(face_right_from_up(metal(td(229), 0.6)), 1, big_one=True)
S['head.tesla'] = tesla((111, 227, 255, 255))
S['head.storm'] = tesla((150, 200, 255, 255), rings=3, spikes=6)
S['head.stun'] = tesla((255, 227, 107, 255), spikes=4)

# The snipers: the minigun's eight-sided hull with one long barrel and a scope.
octagon = body_only(face_right_from_up(td(250)))


def scope(colour):
    def draw(d, c):
        part(d, 'round', box(c, -22, -24, 8, -12), (44, 48, 54, 255), width=2, radius=3)
        disc(d, c, 8, -18, 3.5, colour, width=1)
    return draw


def sniper(body, length, width, tip, lens, extra=None):
    out = gun_head(body, [(0, length, width, (100, 106, 114, 255) if width > 8 else STEEL, tip)])
    im, d, c = canvas()
    scope(lens)(d, c)
    if extra:
        extra(d, c)
    out.alpha_composite(shrink(im))
    return out


def laser(d, c):
    d.line(box(c, 10, -18, 62, -18), fill=(255, 60, 60, 170), width=int(1.4 * U))


S['head.sniper'] = sniper(tint(octagon, 0.22, 0.4, 0.55), 64, 6, 2, (127, 211, 255, 255))
S['head.heavycal'] = sniper(tint(octagon, 0.1, 0.3, 0.45), 66, 10, 4, (127, 211, 255, 255))
S['head.marker'] = sniper(tint(octagon, 0.62, 0.45, 0.65), 64, 6, 2, (255, 70, 60, 255), laser)
S['head.radar'] = radar_dish()
S['head.command'] = command_tent()
S['head.jammer'] = jammer()
S['mine'] = shadowed(scale(tanks('barrelBlack_top'), 0.78), 2, 3, 2, 0.35)
S['mine.big'] = shadowed(scale(tanks('barrelGreen_top'), 1.1), 2, 3, 2, 0.35)
S['spikes'] = shadowed(trim(tanks('barricadeMetal')), 2, 3, 2, 0.35)
S['quarry'] = mine_cart()
S['quarry.drill'] = derrick()
S['quarry.bit'] = drill_bit()
S['quarry.bank'] = scale(tanks('crateMetal'), 1.45)
S['quarry.dial'] = vault_dial()

for name in [n for n in S if n.startswith('head.')]:
    S[name] = centre_head(S[name])

# Enemies, facing right and centred on the body.
S['enemy.bandit'] = character('manBrown_gun')
S['enemy.runner'] = character('womanGreen_stand')
S['enemy.swarm'] = character('zoimbie1_hold')
S['enemy.brute'] = character('robot1_machine')
S['enemy.medic'] = red_cross(white_coat(character('manOld_stand')))
S['enemy.shield'] = riot_shield(character('manBlue_hold'))
S['enemy.ghost'] = ghostly(character('hitman1_silencer'))
S['enemy.sapper'] = aerial(hazard(character('soldier1_stand')))
S['enemy.mother'] = egg_sacs(hue(character('zoimbie1_stand', 1.45), 0.13, sat=0.5, light=0.85))
S['enemy.drone'] = trim(td(271))
S['shadow.drone'] = trim(td(294))
S['enemy.apc'] = square(face_right_from_down(tanks('tank_huge')))
S['enemy.heli'] = heli_body()
S['plane'] = trim(td(270))
S['shadow.plane'] = trim(td(293))
S['rocket'] = trim(face_right_from_up(td(251)))
S['rocket.big'] = trim(face_right_from_up(td(252)))


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
