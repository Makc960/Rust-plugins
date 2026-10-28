"""Builds the game's icon pictures from the two drawings in art/icon:

  icon.webp         the icon, a square picture edge to edge
  icon-cutout.webp  the soldier, sandbags and badge alone, on a clear ground

into
  - the launcher icon, an adaptive icon: the picture fills the 72 dp of its 108 dp layer that
    a phone shows, over a blurred copy for the edges launchers slide into view;
  - the launch screen's emblem: the cutout within the 160 dp disc Android 12 and later draw
    it on (and the older launch screen copies);
  - the 512 px icon for the stores.

    python3 build_icon.py [--preview preview.png]
"""

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

HERE = Path(__file__).resolve().parent
RES = HERE.parent / 'app/src/main/res'
STORE = HERE.parent / 'store/graphics'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
# The launch screen's icon: 240 dp, shown within a disc of 160 dp; made for 4 px per dp.
SPLASH_DP = 240
DISC_DP = 160


def launcher(full):
    for name, k in DENSITIES.items():
        layer = round(108 * k)
        inner = round(72 * k)
        at = (layer - inner) // 2
        front = Image.new('RGBA', (layer, layer), (0, 0, 0, 0))
        front.paste(full.resize((inner, inner), Image.LANCZOS), (at, at))
        back = full.resize((layer, layer), Image.LANCZOS).filter(ImageFilter.GaussianBlur(layer / 20))
        back = ImageEnhance.Brightness(back).enhance(0.75)
        folder = RES / f'mipmap-{name}'
        folder.mkdir(exist_ok=True)
        front.save(folder / 'ic_launcher_foreground.png', optimize=True)
        back.convert('RGB').save(folder / 'ic_launcher_background.png', optimize=True)


def emblem(cutout):
    """The cutout centred in the launch icon's square, every visible pixel inside its disc."""
    cutout = cutout.crop(cutout.getchannel('A').getbbox())
    alpha = np.asarray(cutout.getchannel('A')) > 24
    ys, xs = np.nonzero(alpha)
    # The middle of the drawing as the smallest circle around it would have it, near enough:
    # halfway between its outermost points.
    cx = (xs.min() + xs.max()) / 2
    cy = (ys.min() + ys.max()) / 2
    reach = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()
    size = SPLASH_DP * 4
    scale = (DISC_DP * 4 / 2 * 0.97) / reach
    small = cutout.resize((round(cutout.width * scale), round(cutout.height * scale)), Image.LANCZOS)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(small, (round(size / 2 - cx * scale), round(size / 2 - cy * scale)), small)
    folder = RES / 'drawable-xxxhdpi'
    folder.mkdir(exist_ok=True)
    out.save(folder / 'splash_emblem.png', optimize=True)
    return out


def store(full):
    full.convert('RGB').resize((512, 512), Image.LANCZOS).save(STORE / 'icon-512.png', optimize=True)


def preview(full, splash, path):
    """The launcher icon in a few launchers' shapes, and the launch screen, side by side."""
    k = 4
    layer = 108 * k
    front = Image.open(RES / 'mipmap-xxxhdpi/ic_launcher_foreground.png')
    back = Image.open(RES / 'mipmap-xxxhdpi/ic_launcher_background.png').convert('RGBA')
    icon = Image.alpha_composite(back, front)
    shown = icon.crop((18 * k, 18 * k, 90 * k, 90 * k))
    side = shown.width
    sheet = Image.new('RGB', (side * 4 + 100, side + 40 + SPLASH_DP * 2), (25, 28, 20))
    masks = []
    for kind in ('circle', 'squircle', 'square'):
        m = Image.new('L', (side, side), 0)
        d = ImageDraw.Draw(m)
        if kind == 'circle':
            d.ellipse((0, 0, side - 1, side - 1), fill=255)
        elif kind == 'squircle':
            d.rounded_rectangle((0, 0, side - 1, side - 1), radius=side // 3, fill=255)
        else:
            d.rounded_rectangle((0, 0, side - 1, side - 1), radius=side // 10, fill=255)
        masks.append(m)
    for i, m in enumerate(masks):
        sheet.paste(shown, (20 + i * (side + 20), 20), m)
    tiny = shown.resize((48, 48), Image.LANCZOS)
    sheet.paste(tiny, (20 + 3 * (side + 20), 20), masks[0].resize((48, 48)))
    # The launch screen: the emblem on its disc, at half size.
    s = splash.resize((SPLASH_DP * 2, SPLASH_DP * 2), Image.LANCZOS)
    disc = Image.new('RGBA', s.size, (0, 0, 0, 0))
    r = DISC_DP
    ImageDraw.Draw(disc).ellipse((SPLASH_DP - r, SPLASH_DP - r, SPLASH_DP + r, SPLASH_DP + r), fill=(46, 53, 36, 255))
    sheet.paste(Image.alpha_composite(disc, s), (20, side + 30), Image.alpha_composite(disc, s))
    sheet.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--preview')
    args = parser.parse_args()
    full = Image.open(HERE / 'icon/icon.webp').convert('RGB')
    cutout = Image.open(HERE / 'icon/icon-cutout.webp').convert('RGBA')
    launcher(full)
    splash = emblem(cutout)
    store(full)
    if args.preview:
        preview(full, splash, args.preview)


if __name__ == '__main__':
    main()
