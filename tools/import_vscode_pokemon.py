#!/usr/bin/env python3
"""Import sprite collections distributed as directories of animated GIFs.

Written for editor extensions that ship one folder per creature containing
GIFs named after their state, but the logic is generic: point it at any tree of
GIFs whose file names end in a recognised state and it will convert them.

    python import_vscode_pokemon.py --tree      # inspect the layout
    python import_vscode_pokemon.py --list      # list detected creatures
    python import_vscode_pokemon.py pikachu     # import one
    python import_vscode_pokemon.py --all       # import everything

Source collections usually provide only idle and walk. By default the missing
animations are synthesised from the idle frames using the same transforms the
renderer applies to its placeholder: a hop for happy, a squash for sad, a
horizontal jitter for scared. Pass --no-synth to repeat idle instead.

Requires Pillow:  pip install pillow

Artwork extracted from commercial games is not redistributable. Keep imported
packs in your local sprite directory rather than bundling them into a build.
"""

import argparse
import os
import re
import sys
from collections import defaultdict

try:
    from PIL import Image
except ImportError:
    sys.exit("Falta Pillow.  pip install pillow")


# Source state names mapped onto the animations this plugin reads.
STATE_MAP = {
    "idle": "idle",
    "walk": "walk",
    "run": "walk",
    "walk_fast": "walk",
    "lie": "sleep",
    "sleep": "sleep",
    "sit": "idle",
    "stand": "idle",
    "swipe": "happy",
    "with_ball": "happy",
    "jump": "happy",
    "land": "fall",
    "fall_from_grab": "fall",
    "chase": "walk",
    "climb": "walk",
    "wall_hang": "idle",
}

NEEDED = ["idle", "walk", "sleep", "happy", "sad", "scared", "surprised", "dance", "fall"]

# Directories that never hold creature artwork.
IGNORE_DIRS = {"icon", "icons", "logo", "logos", "img", "images", "dist", "out", "node_modules"}
# Generic container directories. When a sprite sits directly in one of these,
# the creature name has to come from the file name instead.
GENERIC_DIRS = {"media", "gifs", "sprites", "assets", "pokemon", "pokemons"}
# Variants not worth reflecting in the pack id.
NEUTRAL_VARIANTS = {"default", "normal", "base", ""}

DIRECTIONAL = re.compile(r"[_-](left|right)$")
FPS_SUFFIX = re.compile(r"[_-]\d+fps$")


def find_extension_dir():
    home = os.path.expanduser("~")
    roots = [
        os.path.join(home, ".vscode", "extensions"),
        os.path.join(home, ".vscode-insiders", "extensions"),
        os.path.join(home, ".cursor", "extensions"),
        os.path.join(home, ".vscode-server", "extensions"),
    ]
    hits = []
    for root in roots:
        if not os.path.isdir(root):
            continue
        for d in os.listdir(root):
            if "pokemon" in d.lower():
                hits.append(os.path.join(root, d))
    return sorted(hits, reverse=True)


def scan_gifs(ext_dir):
    """Return {creature: {animation: path}} plus any unrecognised states.

    The creature name comes from the containing directory, since collections
    are typically laid out as media/<group>/<creature>/<variant>_<state>.gif.
    The file name prefix is treated as a variant.
    """
    found = defaultdict(dict)      # creature -> anim -> (prioridad, ruta)
    groups = {}                    # creature -> carpeta padre (gen1, gen2...)
    unmapped = set()

    for root, dirs, files in os.walk(ext_dir):
        dirs[:] = [d for d in dirs if d.lower() not in IGNORE_DIRS]
        folder = os.path.basename(root)
        if folder.lower() in IGNORE_DIRS:
            continue

        for f in files:
            if not f.lower().endswith(".gif"):
                continue

            stem = FPS_SUFFIX.sub("", os.path.splitext(f)[0])

            # Directional variants are redundant because the renderer
            # mirrors sprites, but they are kept at low priority as a
            # fallback for creatures that only ship one facing.
            base = DIRECTIONAL.sub("", stem)
            prio = 1 if base != stem else 0

            parts = re.split(r"[_-]", base.lower())

            state = None
            for n in (3, 2, 1):
                if n > len(parts):
                    continue
                cand = "_".join(parts[-n:])
                if cand in STATE_MAP:
                    state = cand
                    break

            if state is None:
                unmapped.add(stem)
                continue

            variant = "_".join(parts[: len(parts) - len(state.split("_"))])

            if folder.lower() in GENERIC_DIRS or not folder:
                # Flat layout: the name lives in the file.
                creature = variant or folder.lower()
            else:
                creature = folder.lower()
                if variant not in NEUTRAL_VARIANTS:
                    creature = f"{creature}-{variant}"

            # The group comes from the parent directory.
            parent = os.path.basename(os.path.dirname(root)).lower()
            if parent and parent not in GENERIC_DIRS:
                groups[creature] = parent

            anim = STATE_MAP[state]
            prev = found[creature].get(anim)
            if prev is None or prio < prev[0]:
                found[creature][anim] = (prio, os.path.join(root, f))

    clean = {c: {a: p for a, (_, p) in anims.items()} for c, anims in found.items()}
    return clean, groups, unmapped


def print_tree(ext_dir, limit=25):
    """Dump the directory layout, for diagnosing an unexpected structure."""
    shown = 0
    for root, dirs, files in os.walk(ext_dir):
        gifs = [f for f in files if f.lower().endswith(".gif")]
        if not gifs:
            continue
        rel = os.path.relpath(root, ext_dir)
        print(f"\n{rel}/   ({len(gifs)} gifs)")
        for f in sorted(gifs)[:6]:
            print(f"    {f}")
        if len(gifs) > 6:
            print(f"    ... and {len(gifs) - 6} more")
        shown += 1
        if shown >= limit:
            print(f"\n(cortado tras {limit} carpetas)")
            return


# --- conversion ---

def gif_frames(path):
    gif = Image.open(path)
    frames = []
    try:
        i = 0
        while True:
            gif.seek(i)
            frames.append(gif.convert("RGBA").copy())
            i += 1
    except EOFError:
        pass
    return frames


def common_bbox(all_frames):
    box = None
    for f in all_frames:
        b = f.getbbox()
        if b is None:
            continue
        box = b if box is None else (
            min(box[0], b[0]), min(box[1], b[1]),
            max(box[2], b[2]), max(box[3], b[3]),
        )
    return box


def squarize(frame, box, side):
    """Crop to the shared bounds and centre in a square, resting on the base."""
    cropped = frame.crop(box)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(cropped, ((side - cropped.width) // 2, side - cropped.height))
    return canvas


# --- synthesis ---

def _shift(img, dx, dy):
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (int(dx), int(dy)))
    return out


def _scale(img, fx, fy):
    """Scale, anchored to the base and horizontally centred."""
    s = img.size[0]
    w = max(1, int(round(s * fx)))
    h = max(1, int(round(s * fy)))
    small = img.resize((w, h), Image.NEAREST)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(small, ((s - w) // 2, s - h))
    return out


def synthesize(base, anim):
    """Derive an animation from the idle frames.

    No new artwork is invented: these are the same transforms the renderer
    applies to its placeholder, baked into PNG files so that reactions remain
    visually distinct even when the source only provides idle and walk.
    """
    b = base[0]
    if anim == "happy":
        return [b, _shift(b, 0, -3), _scale(_shift(b, 0, -5), 0.96, 1.04), _shift(b, 0, -2)]
    if anim == "dance":
        return [_shift(b, -2, -1), _shift(b, 0, -3), _shift(b, 2, -1), _shift(b, 0, -3)]
    if anim == "scared":
        return [_shift(b, -1, 0), _shift(b, 1, 0), _shift(b, -1, -1), _shift(b, 1, 0)]
    if anim == "surprised":
        return [b, _scale(b, 1.08, 1.12), _scale(b, 1.04, 1.06)]
    if anim == "sad":
        return [_scale(b, 1.06, 0.88), _scale(b, 1.08, 0.85)]
    if anim == "sleep":
        return [_scale(b, 1.12, 0.76), _scale(b, 1.14, 0.72)]
    if anim == "fall":
        return [_scale(b, 0.88, 1.14), _scale(b, 0.90, 1.10)]
    return list(base)


def save_strip(frames, path):
    side = frames[0].size[1]
    strip = Image.new("RGBA", (side * len(frames), side), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (i * side, 0))
    strip.save(path)


def import_creature(name, states, outdir, synth=True, group=None):
    loaded = {}
    for anim, path in states.items():
        fr = gif_frames(path)
        if fr:
            loaded[anim] = fr

    if not loaded:
        print(f"  {name}: no usable frames")
        return False

    # The bounding box has to span all animations, otherwise the sprite
    # shifts position between states.
    box = common_bbox([f for frames in loaded.values() for f in frames])
    if box is None:
        print(f"  {name}: fully transparent")
        return False

    side = max(box[2] - box[0], box[3] - box[1])
    squared = {a: [squarize(f, box, side) for f in fr] for a, fr in loaded.items()}

    d = os.path.join(outdir, name)
    os.makedirs(d, exist_ok=True)

    real = sorted(squared.keys())
    for anim, frames in squared.items():
        save_strip(frames, os.path.join(d, f"{anim}.png"))

    # Rellenar lo que falte.
    fallback = squared.get("idle") or next(iter(squared.values()))
    derived = []
    for anim in NEEDED:
        if anim in squared:
            continue
        frames = synthesize(fallback, anim) if synth else list(fallback)
        save_strip(frames, os.path.join(d, f"{anim}.png"))
        derived.append(anim)

    with open(os.path.join(d, "pack.properties"), "w", encoding="utf-8") as f:
        f.write(f"name={name.replace('-', ' ').replace('_', ' ').title()}\n")
        if group:
            f.write(f"gen={group}\n")
        f.write("fps.idle=6\nfps.walk=8\nfps.happy=8\nfps.sleep=2\nfps.scared=12\n")

    tag = f"  (+{len(derived)} synthesised)" if derived else ""
    g = f"[{group}] " if group else ""
    print(f"  {name:28s} {g}{side}px  source: {', '.join(real)}{tag}")
    return True


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("creature", nargs="?")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--tree", action="store_true", help="dump the directory layout")
    ap.add_argument("--no-synth", action="store_true",
                    help="repeat idle instead of synthesising missing animations")
    ap.add_argument("--ext-dir")
    ap.add_argument("--out", default="~/.pokedex-sprites")
    args = ap.parse_args()

    if args.ext_dir:
        ext_dirs = [os.path.expanduser(args.ext_dir)]
    else:
        ext_dirs = find_extension_dir()
        if not ext_dirs:
            sys.exit("Source directory not found. Pass one with --ext-dir.")

    ext_dir = ext_dirs[0]
    print(f"Source:      {ext_dir}")

    if args.tree:
        print_tree(ext_dir)
        return

    found, groups, unmapped = scan_gifs(ext_dir)
    if not found:
        sys.exit("No GIFs with recognisable states found. Try --tree.")

    if args.list:
        print(f"\n{len(found)} creatures:\n")
        for name in sorted(found):
            g = groups.get(name, "?")
            print(f"  {name:28s} [{g}]  {', '.join(sorted(found[name]))}")
        if unmapped:
            print("\nUnmapped states (usually icons or branding):")
            for u in sorted(unmapped)[:20]:
                print(f"  {u}")
        return

    outdir = os.path.expanduser(args.out)
    os.makedirs(outdir, exist_ok=True)
    print(f"Destination: {outdir}\n")

    if args.all:
        targets = sorted(found)
    elif args.creature:
        key = args.creature.lower()
        targets = sorted(n for n in found if key in n)
        if not targets:
            sys.exit(f"No match for '{args.creature}'. Try --list.")
    else:
        sys.exit("Name a creature, or use --all / --list / --tree")

    ok = sum(import_creature(n, found[n], outdir, synth=not args.no_synth,
                             group=groups.get(n)) for n in targets)
    print(f"\n{ok} packs written.")
    print("Reload from Tools > Pokedex in the IDE to pick them up.")


if __name__ == "__main__":
    main()
