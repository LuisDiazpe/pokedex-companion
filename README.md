# Pokedex Companion

A pixel-art companion that lives in a tool window and reacts to what happens in
your IDE. It celebrates a green test run, flinches when a build fails, notices
breakpoints and commits, dozes off when you step away, and dances when music is
playing.

Reactions appear as speech bubbles inside the panel rather than as system
notifications, so the plugin stays out of the way.

Works on every IntelliJ-based IDE: IntelliJ IDEA, PyCharm, WebStorm, GoLand,
Rider, CLion, Android Studio and the rest.

---

## Building

Requires JDK 21 and IntelliJ IDEA to develop against. Android Studio can run the
finished plugin but is not a good environment for building one.

```bash
./gradlew runIde        # launches a sandbox IDE with the plugin loaded
./gradlew test          # simulation unit tests
./gradlew verifyPlugin  # compatibility check against real IDE builds
./gradlew buildPlugin   # produces build/distributions/*.zip
```

On Windows use `gradlew.bat`. If the wrapper is missing, generate it once with
`gradle wrapper --gradle-version 8.10.2`, or simply open `build.gradle.kts` in
IntelliJ IDEA and let the IDE import the project.

---

## How it works

### Layers

```
world/     Simulation. Plain Kotlin, no Swing and no platform types.
events/    Event bus, reaction table, pomodoro service, IDE listeners.
render/    Sprite loading and the Swing panel that draws the world.
ui/        Tool window, settings page, actions, spawn dialog.
music/     Per-platform now-playing detection behind one interface.
tools/     Python utilities for preparing sprite packs.
```

The separation matters in one specific way: `world/` has no dependency on Swing
or the IntelliJ Platform, which means the entire behaviour model runs under
plain JUnit without launching an IDE. The bounds invariants in
`PetBoundsTest.kt` exist because of that.

### Rendering

The panel paints directly with `Graphics2D` rather than embedding a browser.
A `javax.swing.Timer` drives the loop at 30 fps and does nothing while the tool
window is hidden, so an unopened panel costs nothing. Sprites are drawn with
nearest-neighbour interpolation to keep pixel art crisp, and mirrored with an
`AffineTransform` so a pack only ever needs one facing.

### Bounds handling

World bounds are recomputed from the panel's current size on every frame and are
never cached. After each integration step the position is clamped, and a clamp
that changed the position is treated as a collision that resolves into a new
state. An airborne pet already resting on the floor lands unconditionally.

Together these rules make it impossible for a pet to drift outside the panel or
to keep descending toward a floor that no longer exists, which is the usual
failure mode when the tool window is resized or collapsed and reopened.

### Events and priority

IDE listeners publish to a project-level bus; the panel subscribes. Every event
carries a priority, and a reaction already playing is only interrupted by
something higher:

| Priority | Events |
|---|---|
| 10 | Pomodoro transitions |
| 9  | Build failed |
| 8  | Test results, commit succeeded |
| 7  | Build succeeded |
| 6  | Breakpoint hit, process exit, live error count |
| 5  | Build started, run started |
| 4  | Petting, music started, encounters |
| 3  | Returned from idle, music stopped |
| 2  | Typing burst |
| 1  | Went idle |

Without this, a keystroke burst would cut off the animation for a failing build.

Reactions themselves live in a single file, `events/Reactions.kt`. Animation
choice and dialogue for every event are decided there and nowhere else, so
tuning how the pets feel does not touch any other code.

### Personalities

Each pet is assigned one of four profiles that adjust movement speed, how often
it rests, how often it runs, how often it jumps, and some of its dialogue. Two
pets sharing a sprite pack still behave distinctly. Pets that come within range
of each other exchange a greeting, with the reply delayed by a second so the
bubbles read as a conversation.

---

## Sprite packs

A pack is a directory of PNG files. There is no manifest to write:

```
mypet/
├── idle.png
├── walk.png
├── happy.png
├── sad.png
├── scared.png
├── surprised.png
├── dance.png
├── sleep.png
├── fall.png
└── pack.properties   (optional)
```

**The convention:** each file holds its frames in a single horizontal strip, and
the image height defines the frame size. A `walk.png` measuring 128×32 is four
32×32 frames. Nothing else is inferred.

Missing animations fall back to `idle`, so a pack containing only `idle.png` and
`walk.png` already works. Do not draw left- and right-facing versions; the
renderer mirrors the sprite itself.

`pack.properties` is optional and overrides defaults:

```properties
name=My Pet
gen=forest
fps.walk=8
fps.idle=4
loop.happy=false
```

The `gen` value becomes a filter entry in the spawn dialog, which is how the
bundled creatures are grouped.

### Installing a pack

Drop the folder into the sprite directory, then use **Tools → Pokédex → Recargar
Sprite Packs**. The directory path is configurable in settings and defaults to
`~/.pokedex-sprites`. **Tools → Pokédex → Abrir Carpeta de Sprites** opens it and
creates it if needed.

Packs on disk take precedence over bundled packs sharing the same id, so a
bundled creature can be replaced without touching the plugin.

### Preparing artwork

Most sprite sheets found in the wild are not laid out as horizontal strips.
`tools/make_strips.py` converts the common layouts:

```bash
pip install pillow

# Grid sheet. "idle:0:4" means row 0, four frames.
python tools/make_strips.py grid sheet.png out/ --frame 32 \
    --row idle:0:4 --row walk:1:4 --row happy:3:3

# Animated GIF
python tools/make_strips.py gif walk.gif out/walk.png

# Numbered PNGs
python tools/make_strips.py join "frames/walk_*.png" out/walk.png

# Trim the shared transparent margin and square the frames
python tools/make_strips.py tidy out/
```

The `tidy` step is worth running on anything imported. It computes a bounding box
across *all* animations of a creature rather than per file, which prevents the
sprite from jumping position when the animation changes.

`tools/import_vscode_pokemon.py` handles collections distributed as directories
of animated GIFs, mapping their state names onto the ones this plugin uses and
synthesising the missing emotional animations from the idle frames.

### Generating creatures

The bundled artwork is produced by `tools/make_creatures.py`, which draws each
frame procedurally. Editing the `PALETTES` and `CREATURES` tables at the top of
that file is enough to add variants:

```bash
python tools/make_creatures.py src/main/resources/sprites --preview /tmp/sheet.png
```

The `--preview` flag writes a contact sheet scaled 5× for reviewing the result.
Remember to update `src/main/resources/sprites/index.txt` after adding a pack,
since classpath resources inside a jar cannot be listed at runtime.

---

## Languages

The interface and all pet dialogue ship in English and Spanish. By default the
plugin follows the IDE's language; the settings page can pin it to either one,
which is useful when running an English IDE but wanting the companion to speak
something else.

Strings live in `src/main/resources/messages/`. `PokedexBundle.properties` is
the base bundle and `PokedexBundle_es.properties` overrides individual keys, so
an untranslated key falls back rather than showing a placeholder. Adding a
language means dropping in `PokedexBundle_<code>.properties` and adding the code
to the list in `PetConfigurable`.

Two conventions in the bundle are worth knowing:

- Keys ending in a number form a list. `react.build.ok.1` through
  `react.build.ok.3` are interchangeable lines and one is picked at random,
  which is how a pet avoids repeating itself.
- A personality name before the number overrides that list for one personality.
  `react.build.ok.LAZY.1` replaces the whole default list for lazy pets and
  leaves the others untouched.

Files are read as UTF-8, so accents go in directly. Values containing a `{0}`
placeholder pass through `MessageFormat`, where a literal apostrophe has to be
doubled; keys without placeholders are used verbatim.

---

## Configuration

**Settings → Tools → Pokédex**

| Setting | Notes |
|---|---|
| Language | Automatic, English or Spanish |
| Movement mode | `GROUND` walks the floor with gravity and jumps; `ROAM_2D` roams the full panel |
| Follow cursor | Pets approach the mouse while it moves across the panel |
| Sprite size | Rendered edge length; physics use the same value |
| Sprite pack directory | Defaults to `~/.pokedex-sprites` |
| IDE reactions | Builds, tests, debug sessions, commits |
| Live error reactions | Error count of the file currently open |
| Music reactions | Off by default; see below |
| Idle timeout | Minutes of inactivity before a pet falls asleep |
| Pomodoro | Focus and break lengths |

The movement mode also has a toggle in the tool window title bar.

### Music detection

Disabled by default because every implementation spawns an external process.
To enable it, change `MusicProvider.default()` to return `detect()` and tick the
setting.

| Platform | Mechanism | Requirement |
|---|---|---|
| Linux | MPRIS over D-Bus | `playerctl` on the PATH |
| macOS | AppleScript against Spotify and Music | Automation permission on first run |
| Windows | System media transport controls via WinRT | None |

Polling runs on a background executor every three seconds and emits an event
only when the track changes.

---

## Known limitations

`CodeAnalysisListener` reads the current error count through
`DaemonCodeAnalyzerImpl`, which is internal platform API. Every call is guarded,
so the plugin degrades gracefully if it stops resolving in a future release, but
`verifyPlugin` will flag it. Removing that one listener and its registration in
`plugin.xml` is a clean way to drop the dependency.

The `CommitSucceeded` event is defined and reachable from the debug action, but
no listener is wired up in this build. Adding one means implementing
`CheckinHandlerFactory`, registering it through a `config-file` guarded by an
optional dependency on `com.intellij.modules.vcs`, and accepting that the IDE
inspector cannot resolve that module id from a downloaded platform artifact.

Pet positions and the current roster are not persisted between sessions.

---

## Licensing

The plugin source is available under the MIT license.

The twelve bundled sprite packs are original artwork generated by
`tools/make_creatures.py` and are covered by the same license.

Sprite packs you install yourself remain subject to their own terms. Artwork
extracted from commercial games is not redistributable; keep it in your local
sprite directory rather than bundling it into a build.
