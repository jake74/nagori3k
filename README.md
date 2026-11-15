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

Early. There is no app yet. The emulator core runs Tempest 3000 on Apple
silicon Macs at the speed of the original hardware; the apps are planned
and not started.

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
