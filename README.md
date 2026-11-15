# nagori3k

*Nagori* (名残): the trace that lingers after something has passed.

nagori3k exists to bring **Tempest 3000** back for fans of Jeff Minter. The
game was released in 2000 for NUON, a games platform built into a handful
of DVD players, and it has been out of reach for most people ever since:
the players are scarce, and for years no emulator could run it at speed.

The aim is a native app for Mac, and then Android, that plays Tempest 3000
properly, with nothing to configure:

- a library built from a folder of your own disc images;
- controller support and button remapping;
- the scanline look switched on or off;
- an on-screen controller on Android phones and tablets.

There is no iPhone or iPad version planned for now. Tempest 3000 only runs
at speed with a dynamic compiler, and Apple does not allow apps on those
devices to use one.

Other NUON games should work too, but Tempest 3000 is the one this project
is measured against.

## Status

Early. The emulator core runs Tempest 3000 on Apple silicon Macs at the
speed of the original hardware, and there is a first version of the Mac app:

- a library built from a folder you choose, with cover art;
- play in a window or full screen, with a game controller or the keyboard.
  In full screen the game has the whole display: Command-F enters and
  leaves it, Escape leaves it, and the pointer is put away;
- settings for the analogue stick (sensitivity and weight), scanlines, the
  30 frames-a-second limit, stretching the 4:3 picture to fill a wide
  screen (off unless you ask), and Tempest 3000's high scores;
- button remapping for controller and keyboard.

It needs macOS 14 or later on an Apple silicon Mac. It is built by
`./build.sh` into `build/nagori3k.app`, signed for the Mac it was built on
only. There is no download yet.

Tempest 3000 has no widescreen mode: it never asks the system what shape
the screen is. Stretching the picture is the only way to fill a 16:9
display, and it makes everything a third wider than it was drawn.

Cover art is not bundled. The app looks beside each game for a picture
with the same name (or `cover`, `folder` or `boxart`, `.png` or `.jpg`),
and otherwise asks Wikipedia for the cover of an article with exactly the
game's title, keeping what it finds in
`~/Library/Application Support/nagori3k/boxart`. A game with no match gets
a plain tile.

Underneath the app:

- `host/` - the host layer: loads the core and gives a frontend the
  picture (an OpenGL texture), the sound, the controller and the settings
  through a small C interface (`host/nagori_host.h`).
- `mac/nagori_cli.mm` - a window, sound, a game controller and the
  keyboard, and nothing else.

### Building and running

On an Apple silicon Mac with Xcode's command-line tools and CMake:

```
git clone --recurse-submodules https://github.com/jake74/nagori3k
cd nagori3k
./build.sh
open build/nagori3k.app                         # the app
build/nagori-cli "/path/to/Tempest 3000.iso"    # or the bare player
```

For scripts and tests the app takes `--play <disc image>`,
`--quit-after <seconds>`, `--full-screen yes` and `--snapshot <directory>`
(which writes pictures of its screens and quits). Each option needs a
value: AppKit takes a word left over on the command line as a file to
open, and then no window appears.

In the bare player, a controller is picked up if one is connected: left stick or d-pad to move,
the face buttons to fire and jump, Options/Menu for Start. On the keyboard:
arrows, Z (fire), X, Return (Start), Esc to quit. Dragging the mouse in the
window turns the claw like a spinner.

Settings are passed as `--option key=value`; the ones that matter:

| Option | Values | What it does |
|---|---|---|
| `nuance_controller` | `analog stick`, `d-pad only` | The left stick drives a weighted flywheel, in the spirit of the arcade Tempest's spinner. The d-pad always works. |
| `nuance_spinner_sensitivity` | `10%` to `400%` | How fast full stick turns. |
| `nuance_spinner_inertia` | `0%` to `100%` | The flywheel's weight. Default `70%`. |
| `nuance_max_fps` | `30`, `20`, `15`, `unlimited` | Tempest 3000 plays faster the faster it draws; 30 is right for it. |
| `nuance_scanlines` | `enabled`, `disabled` | The CRT look. |
| `nuance_t3k_high_scores` | `keep`, `do not keep`, `erase` | The original lost its high scores at power-off. These are kept. |
| `nuance_skip_controller_message` | `enabled`, `disabled` | Skips the game's "controller configuration has changed" screen. |

## Standing on other people's work

None of this would exist without the people below, and nagori3k is a thin
layer on top of what they built. It is offered with great respect for their
work.

- **Mike Perry** wrote **Nuance**, the NUON emulator, between 2002 and
  2007. He did it in a clean room, from the NUON SDK's architecture
  documents and his own tools, and then released the source. Emulating four
  custom processors and their BIOS from documentation alone is a remarkable
  piece of work, and it is the foundation of everything here.
- **Carsten Wächter (toxie)** picked the released source up in 2020, in
  honour of its original author, and has carried it forward as
  **[NuanceResurrection](https://github.com/andkrau/NuanceResurrection)**
  together with its contributors. Years of careful fixes to timing, video,
  audio and the dynamic compiler are why games run as well as they do.
- **WizzardSK** ported NuanceResurrection to Linux and made it a libretro
  core, which is what makes it possible to put a new front end on it at
  all.
- **Everyone else who has contributed to NuanceResurrection**, and the
  NUON community that has kept the platform, its documentation and its
  games alive for a quarter of a century.
- **Jeff Minter and Llamasoft**, for Tempest 3000 itself, and **VM Labs**,
  for the strange and ambitious machine it ran on.

The emulator core used here is a fork of NuanceResurrection,
[jake74/NuanceResurrection](https://github.com/jake74/NuanceResurrection),
included as a submodule in `core/`. The fork adds an Arm64 dynamic compiler
and macOS support. If you want a NUON emulator for Windows or Linux, or
want to help with emulation itself, NuanceResurrection is the project to go
to.

## What this project does not include

- **No games.** You need your own Tempest 3000 disc and an image made from
  it.
- **No VM Labs BIOS.** The core uses NuanceResurrection's own clean-room
  BIOS replacement.

## Licence and trademarks

The emulator core is covered by the Nuance licence in `core/license.txt`.
Among other terms, it may not be redistributed for profit, by itself or as
part of any other package. nagori3k is free, and will stay free, with no
advertising and no purchases.

NUON is or was a trademark of Genesis Microchip, Inc. Tempest is a
trademark of Atari. This project is not affiliated with or endorsed by
either, or by Llamasoft.
