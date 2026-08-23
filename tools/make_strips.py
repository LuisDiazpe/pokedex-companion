"""Convert sprite sheets into the horizontal-strip layout this plugin expects.

The plugin reads one PNG per animation, frames laid out left to right, with the
image height defining the frame size. Most artwork found elsewhere uses a grid
sheet, an animated GIF, or numbered files, so this script bridges the gap.

Requires Pillow:  pip install pillow

Grid sheet, one row per animation:

    python make_strips.py grid sheet.png out/ --frame 32 \\
        --row idle:0:4 --row walk:1:4 --row happy:3:3

    "idle:0:4" reads as: animation idle, row 0, four frames.

Animated GIF:

    python make_strips.py gif walk.gif out/walk.png

Numbered PNGs:

    python make_strips.py join "frames/walk_*.png" out/walk.png

Trim the shared transparent margin and square every frame. Worth running on
anything imported, because the bounding box spans all animations rather than
each file, which stops the sprite from shifting position between states:

    python make_strips.py tidy out/
"""

import argparse
import glob
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is required:  pip install pillow")


ANIMS = ["idle", "walk", "sleep", "happy", "sad",
         "scared", "surprised", "dance", "fall"]


def save_strip(frames, out_path):
    """Writes a list of frames as one horizontal strip."""
    if not frames:
        return False
    w, h = frames[0].size
    strip = Image.new("RGBA", (w * len(frames), h), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f.convert("RGBA"), (i * w, 0))
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    strip.save(out_path)
    print(f"  {os.path.basename(out_path)}  {len(frames)} frames de {w}x{h}")
    return True


def cmd_grid(args):
    sheet = Image.open(args.sheet).convert("RGBA")
    fs = args.frame
    cols = sheet.width // fs

    print(f"Sheet {sheet.width}x{sheet.height} -> {cols} columns of {fs}px")

    for spec in args.row:
        parts = spec.split(":")
        if len(parts) != 3:
            print(f"  ! malformed spec: {spec}  (expected name:row:frames)")
            continue
        name, row, count = parts[0], int(parts[1]), int(parts[2])

        if name not in ANIMS:
            print(f"  ! warning: '{name}' is not an animation the plugin reads")

        frames = []
        for c in range(min(count, cols)):
            box = (c * fs, row * fs, (c + 1) * fs, (row + 1) * fs)
            if box[2] > sheet.width or box[3] > sheet.height:
                break
            frames.append(sheet.crop(box))

        save_strip(frames, os.path.join(args.outdir, f"{name}.png"))

    write_props(args.outdir)


def cmd_gif(args):
    gif = Image.open(args.gif)
    frames = []
    try:
        while True:
            frames.append(gif.convert("RGBA").copy())
            gif.seek(gif.tell() + 1)
    except EOFError:
        pass
    save_strip(frames, args.out)


def cmd_join(args):
    paths = sorted(glob.glob(args.pattern))
    if not paths:
        sys.exit(f"No files match: {args.pattern}")
    frames = [Image.open(p).convert("RGBA") for p in paths]
    save_strip(frames, args.out)


def cmd_tidy(args):
    """Trims the shared transparent margin and squares every frame."""
    files = [f for f in os.listdir(args.dir) if f.endswith(".png")]
    if not files:
        sys.exit("No PNG files found")

    # The bounding box spans every frame of every animation, so the sprite
    # keeps its position when the animation changes.
    box = None
    for f in files:
        img = Image.open(os.path.join(args.dir, f)).convert("RGBA")
        h = img.height
        n = max(1, img.width // h)
        for i in range(n):
            b = img.crop((i * h, 0, (i + 1) * h, h)).getbbox()
            if b is None:
                continue
            box = b if box is None else (
                min(box[0], b[0]), min(box[1], b[1]),
                max(box[2], b[2]), max(box[3], b[3]),
            )

    if box is None:
        sys.exit("Fully transparent, nothing to trim")

    side = max(box[2] - box[0], box[3] - box[1])
    print(f"Shared bounds: {box} -> {side}x{side} frames")

    for f in files:
        path = os.path.join(args.dir, f)
        img = Image.open(path).convert("RGBA")
        h = img.height
        n = max(1, img.width // h)
        frames = []
        for i in range(n):
            fr = img.crop((i * h + box[0], box[1], i * h + box[2], box[3]))
            canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
            canvas.paste(fr, ((side - fr.width) // 2, side - fr.height))
            frames.append(canvas)
        save_strip(frames, path)


def write_props(outdir):
    p = os.path.join(outdir, "pack.properties")
    if os.path.exists(p):
        return
    with open(p, "w", encoding="utf-8") as f:
        f.write(f"name={os.path.basename(os.path.abspath(outdir))}\n")
        f.write("fps.idle=4\nfps.walk=8\nfps.dance=10\n")
    print("  pack.properties written")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("grid", help="grid sheet to strips")
    g.add_argument("sheet")
    g.add_argument("outdir")
    g.add_argument("--frame", type=int, required=True, help="frame size in pixels")
    g.add_argument("--row", action="append", required=True,
                   metavar="nombre:fila:frames")
    g.set_defaults(func=cmd_grid)

    gi = sub.add_parser("gif", help="animated GIF to strip")
    gi.add_argument("gif")
    gi.add_argument("out")
    gi.set_defaults(func=cmd_gif)

    j = sub.add_parser("join", help="numbered PNGs to strip")
    j.add_argument("pattern")
    j.add_argument("out")
    j.set_defaults(func=cmd_join)

    t = sub.add_parser("tidy", help="trim and square the frames in a directory")
    t.add_argument("dir")
    t.set_defaults(func=cmd_tidy)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
