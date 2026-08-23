#!/usr/bin/env python3
"""Generate the original creature sprite packs bundled with the plugin.

Every frame is drawn procedurally, so palettes and silhouettes can be changed
without touching an image editor. Edit the PALETTES and CREATURES tables below
to add variants.

Requires Pillow:  pip install pillow

    python make_creatures.py src/main/resources/sprites --preview /tmp/sheet.png

The --preview flag writes a contact sheet scaled 5x for reviewing the result.
After adding a pack, update src/main/resources/sprites/index.txt: classpath
resources inside a jar cannot be listed at runtime, so the index is what tells
the plugin which packs it ships with.
"""

import argparse
import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("Falta Pillow.  pip install pillow")

S = 32  # lado del frame


# --- palettes ---

def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (255,)


PALETTES = {
    # Forest
    "sprout": dict(main=(122, 201, 132), belly=(226, 240, 205),
                   accent=(240, 150, 160), eye=(38, 46, 52)),
    "moss":   dict(main=(96, 158, 110), belly=(206, 226, 190),
                   accent=(226, 190, 120), eye=(34, 44, 38)),
    "bloom":  dict(main=(214, 152, 190), belly=(248, 226, 238),
                   accent=(230, 120, 150), eye=(56, 36, 50)),
    "fern":   dict(main=(150, 196, 108), belly=(232, 242, 206),
                   accent=(200, 156, 96), eye=(40, 48, 34)),

    # Ember
    "ember":  dict(main=(232, 138, 84), belly=(250, 224, 190),
                   accent=(226, 106, 96), eye=(48, 34, 34)),
    "sand":   dict(main=(224, 196, 122), belly=(248, 238, 210),
                   accent=(226, 148, 120), eye=(56, 44, 32)),
    "clay":   dict(main=(198, 122, 100), belly=(240, 214, 196),
                   accent=(232, 168, 120), eye=(52, 34, 30)),
    "ash":    dict(main=(150, 140, 148), belly=(226, 222, 228),
                   accent=(232, 140, 110), eye=(40, 38, 44)),

    # Tide
    "ripple": dict(main=(110, 176, 224), belly=(214, 236, 248),
                   accent=(240, 160, 170), eye=(32, 44, 60)),
    "dusk":   dict(main=(166, 132, 214), belly=(230, 220, 245),
                   accent=(238, 152, 186), eye=(40, 32, 52)),
    "frost":  dict(main=(160, 210, 220), belly=(232, 248, 250),
                   accent=(178, 200, 240), eye=(34, 50, 58)),
    "deep":   dict(main=(96, 122, 190), belly=(200, 214, 244),
                   accent=(150, 200, 226), eye=(28, 34, 56)),
}

# Ear shape drives the silhouette, which is what separates one creature from
# another at this resolution.
EAR_STYLES = ["pointy", "round", "long", "tuft"]


class Canvas:
    """Pixel-level canvas. No antialiasing; this is pixel art."""

    def __init__(self):
        self.img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def blob(self, x0, y0, x1, y1, fill, outline):
        """Ellipse with a one pixel outline, which is what makes it read."""
        self.d.ellipse([x0 - 1, y0 - 1, x1 + 1, y1 + 1], fill=outline)
        self.d.ellipse([x0, y0, x1, y1], fill=fill)

    def box(self, x0, y0, x1, y1, fill, outline=None):
        if outline:
            self.d.rectangle([x0 - 1, y0 - 1, x1 + 1, y1 + 1], fill=outline)
        self.d.rectangle([x0, y0, x1, y1], fill=fill)

    def px(self, x, y, c):
        if 0 <= x < S and 0 <= y < S:
            self.img.putpixel((int(x), int(y)), c)


def draw_creature(pal, ears, *, dy=0, dx=0, squash=0, eyes="open",
                  mouth="none", ear_droop=0, leg_phase=0, tail_up=0):
    """Draw a single frame.

    Every parameter is an offset, so animating is a matter of calling this
    repeatedly with different values.
    """
    c = Canvas()
    main = pal["main"] + (255,)
    belly = pal["belly"] + (255,)
    accent = pal["accent"] + (255,)
    eye = pal["eye"] + (255,)
    outline = shade(pal["main"], 0.40)
    dark = shade(pal["main"], 0.70)
    white = (255, 255, 255, 255)

    cx = 16 + dx
    foot_b = 30 + dy                 # suela de las patas
    body_b = foot_b - 3              # donde acaba el cuerpo
    bh = 15 - squash                 # alto del cuerpo
    bw = 8 + (squash + 1) // 2       # medio ancho
    top = body_b - bh                # coronilla

    # Feet, peeking out below the body
    lift_l = 2 if leg_phase == 1 else 0
    lift_r = 2 if leg_phase == 2 else 0
    c.blob(cx - 7, foot_b - 3 - lift_l, cx - 3, foot_b - lift_l, dark, outline)
    c.blob(cx + 3, foot_b - 3 - lift_r, cx + 7, foot_b - lift_r, dark, outline)

    # Tail, kept outside the body silhouette
    ty = body_b - 7 - tail_up
    c.blob(cx - bw - 4, ty - 2, cx - bw, ty + 2, main, outline)

    # Ears, drawn behind the body
    ey = top + 2 + ear_droop
    if ears == "pointy":
        for sx in (-1, 1):
            ex = cx + sx * 5
            c.d.polygon([(ex - 3, ey + 2), (ex + 3, ey + 2), (ex + sx * 2, ey - 7)],
                        fill=outline)
            c.d.polygon([(ex - 2, ey + 1), (ex + 2, ey + 1), (ex + sx * 2, ey - 5)],
                        fill=main)
    elif ears == "round":
        for sx in (-1, 1):
            c.blob(cx + sx * 6 - 3, ey - 6, cx + sx * 6 + 3, ey + 1, main, outline)
    elif ears == "long":
        for sx in (-1, 1):
            bx = cx + sx * 4
            c.blob(bx - 2, ey - 10, bx + 2, ey + 1, main, outline)
            c.d.rectangle([bx - 1, ey - 8, bx + 1, ey - 3], fill=accent)
    elif ears == "tuft":
        for sx in (-1, 1):
            c.blob(cx + sx * 6 - 2, ey - 4, cx + sx * 6 + 2, ey + 1, main, outline)
        c.d.polygon([(cx - 3, ey), (cx + 3, ey), (cx, ey - 8)], fill=outline)
        c.d.polygon([(cx - 2, ey - 1), (cx + 2, ey - 1), (cx, ey - 6)], fill=main)

    # Body
    c.blob(cx - bw, top, cx + bw, body_b, main, outline)

    # Belly, lower half only
    c.blob(cx - bw + 3, top + bh // 2, cx + bw - 3, body_b - 1, belly, belly)

    # Eyes
    eyy = top + 5
    lx, rx = cx - 4, cx + 4
    if eyes == "closed":
        for x in (lx, rx):
            c.d.line([(x - 2, eyy), (x + 2, eyy)], fill=eye)
    elif eyes == "happy":
        for x in (lx, rx):
            c.d.line([(x - 2, eyy + 1), (x, eyy - 1)], fill=eye)
            c.d.line([(x, eyy - 1), (x + 2, eyy + 1)], fill=eye)
    elif eyes == "wide":
        for x in (lx, rx):
            c.blob(x - 2, eyy - 2, x + 2, eyy + 3, white, eye)
            c.d.rectangle([x - 1, eyy, x, eyy + 2], fill=eye)
    elif eyes == "sad":
        for x in (lx, rx):
            c.d.rectangle([x - 1, eyy + 1, x, eyy + 3], fill=eye)
            c.d.line([(x - 2, eyy - 1), (x + 1, eyy)], fill=eye)
    else:  # open
        for x in (lx, rx):
            c.d.rectangle([x - 1, eyy - 1, x, eyy + 2], fill=eye)
            c.px(x - 1, eyy - 1, white)

    # Cheeks
    if eyes in ("open", "happy"):
        for sx in (-1, 1):
            c.px(cx + sx * 7, eyy + 3, accent)
            c.px(cx + sx * 6, eyy + 3, accent)

    # Mouth
    my = eyy + 5
    if mouth == "smile":
        c.d.line([(cx - 2, my - 1), (cx - 1, my)], fill=eye)
        c.d.line([(cx - 1, my), (cx + 1, my)], fill=eye)
        c.d.line([(cx + 1, my), (cx + 2, my - 1)], fill=eye)
    elif mouth == "frown":
        c.d.line([(cx - 2, my), (cx - 1, my - 1)], fill=eye)
        c.d.line([(cx - 1, my - 1), (cx + 1, my - 1)], fill=eye)
        c.d.line([(cx + 1, my - 1), (cx + 2, my)], fill=eye)
    elif mouth == "open":
        c.d.ellipse([cx - 2, my - 2, cx + 2, my + 2], fill=eye)
    elif mouth == "flat":
        c.d.line([(cx - 2, my), (cx + 2, my)], fill=eye)

    return c.img


# --- animations ---

def build_animations(pal, ears):
    A = {}

    # Breathing, ears still
    A["idle"] = [
        draw_creature(pal, ears, squash=0, mouth="smile"),
        draw_creature(pal, ears, squash=1, dy=1, mouth="smile"),
        draw_creature(pal, ears, squash=0, mouth="smile"),
        draw_creature(pal, ears, squash=0, dy=-1, mouth="smile"),
    ]

    # Alternating feet with a bounce
    A["walk"] = [
        draw_creature(pal, ears, leg_phase=1, dy=-1, mouth="smile"),
        draw_creature(pal, ears, leg_phase=0, dy=0, mouth="smile"),
        draw_creature(pal, ears, leg_phase=2, dy=-1, mouth="smile"),
        draw_creature(pal, ears, leg_phase=0, dy=0, mouth="smile"),
    ]

    A["sleep"] = [
        draw_creature(pal, ears, eyes="closed", squash=3, ear_droop=4),
        draw_creature(pal, ears, eyes="closed", squash=4, ear_droop=5),
    ]

    A["happy"] = [
        draw_creature(pal, ears, eyes="happy", mouth="smile", dy=0),
        draw_creature(pal, ears, eyes="happy", mouth="smile", dy=-3, squash=-1),
        draw_creature(pal, ears, eyes="happy", mouth="open", dy=-5, squash=-1),
        draw_creature(pal, ears, eyes="happy", mouth="smile", dy=-2),
    ]

    A["sad"] = [
        draw_creature(pal, ears, eyes="sad", mouth="frown", squash=2, ear_droop=5),
        draw_creature(pal, ears, eyes="sad", mouth="frown", squash=2, dy=1, ear_droop=6),
    ]

    A["scared"] = [
        draw_creature(pal, ears, eyes="wide", mouth="open", dx=-1, ear_droop=-1),
        draw_creature(pal, ears, eyes="wide", mouth="open", dx=1, ear_droop=-1),
        draw_creature(pal, ears, eyes="wide", mouth="open", dx=-1, dy=-1),
        draw_creature(pal, ears, eyes="wide", mouth="open", dx=1),
    ]

    A["surprised"] = [
        draw_creature(pal, ears, eyes="wide", mouth="open", squash=0),
        draw_creature(pal, ears, eyes="wide", mouth="open", squash=-2, dy=-2, ear_droop=-2),
        draw_creature(pal, ears, eyes="wide", mouth="open", squash=-1, dy=-1, ear_droop=-1),
    ]

    A["dance"] = [
        draw_creature(pal, ears, eyes="happy", mouth="smile", dx=-2, dy=-1, tail_up=1),
        draw_creature(pal, ears, eyes="happy", mouth="open", dx=0, dy=-3, tail_up=2),
        draw_creature(pal, ears, eyes="happy", mouth="smile", dx=2, dy=-1, tail_up=1),
        draw_creature(pal, ears, eyes="happy", mouth="open", dx=0, dy=-3, tail_up=2),
    ]

    A["fall"] = [
        draw_creature(pal, ears, eyes="wide", mouth="open", squash=-3, ear_droop=-3, tail_up=2),
        draw_creature(pal, ears, eyes="wide", mouth="open", squash=-2, ear_droop=-2, tail_up=1),
    ]

    return A


def save_strip(frames, path):
    strip = Image.new("RGBA", (S * len(frames), S), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (i * S, 0))
    strip.save(path)


def build_pack(name, pal_key, ears, outdir, group=None):
    pal = PALETTES[pal_key]
    d = os.path.join(outdir, name)
    os.makedirs(d, exist_ok=True)

    anims = build_animations(pal, ears)
    for anim, frames in anims.items():
        save_strip(frames, os.path.join(d, f"{anim}.png"))

    with open(os.path.join(d, "pack.properties"), "w", encoding="utf-8") as f:
        f.write(f"name={name.capitalize()}\n")
        if group:
            f.write(f"gen={group}\n")
        f.write("fps.idle=4\nfps.walk=8\nfps.happy=10\n")
        f.write("fps.dance=8\nfps.scared=12\nfps.sleep=2\n")

    print(f"  {name:10s} [{group or '-'}] {len(anims)} animaciones")
    return anims


# (name, palette, ears, group)
# The group becomes a filter entry in the plugin's spawn dialog.
CREATURES = [
    ("sprig",   "sprout", "pointy", "bosque"),
    ("mossy",   "moss",   "round",  "bosque"),
    ("petal",   "bloom",  "long",   "bosque"),
    ("fernie",  "fern",   "tuft",   "bosque"),

    ("cinder",  "ember",  "tuft",   "brasa"),
    ("dune",    "sand",   "round",  "brasa"),
    ("terra",   "clay",   "pointy", "brasa"),
    ("cinder2", "ash",    "long",   "brasa"),

    ("puddle",  "ripple", "round",  "marea"),
    ("mochi",   "dusk",   "long",   "marea"),
    ("glacio",  "frost",  "pointy", "marea"),
    ("abyss",   "deep",   "tuft",   "marea"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outdir", help="destination directory")
    ap.add_argument("--preview", help="write a contact sheet here")
    args = ap.parse_args()

    outdir = os.path.expanduser(args.outdir)
    os.makedirs(outdir, exist_ok=True)
    print(f"Writing to {outdir}")

    all_anims = {}
    for name, pal, ears, group in CREATURES:
        all_anims[name] = build_pack(name, pal, ears, outdir, group)

    if args.preview:
        make_preview(all_anims, args.preview)
        print(f"Preview: {args.preview}")


def make_preview(all_anims, path):
    """Contact sheet scaled 5x for reviewing the artwork."""
    order = ["idle", "walk", "happy", "sad", "scared", "surprised", "dance", "sleep", "fall"]
    cols = max(len(v[a]) for v in all_anims.values() for a in order)
    rows = sum(len(order) for _ in all_anims)

    sheet = Image.new("RGBA", (S * cols, S * rows), (28, 30, 34, 255))
    r = 0
    for name, anims in all_anims.items():
        for a in order:
            for i, f in enumerate(anims[a]):
                sheet.paste(f, (i * S, r * S), f)
            r += 1

    sheet = sheet.resize((sheet.width * 5, sheet.height * 5), Image.NEAREST)
    sheet.save(path)


if __name__ == "__main__":
    main()
