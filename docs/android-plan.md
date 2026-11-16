# nagori3k for Android: plan

Status: phase 0 done on 2026-11-15, and the answer is go. Phase 1 is next.

## Phase 0 result

On the Xiaomi Pad Mini (MediaTek MT8799, Android 16), run from `adb shell`
with no app and nothing drawn:

- The Arm64 compiler works unchanged: self-test passes, and the
  differential test compared 180,563 random trials with no mismatch.
- Tempest 3000, level one, with the 30 frames-a-second limit: 29.2-29.8
  (the M4 Pro gives 29.5-30.0 in the same test).
- With no limit: 41-53 on the Pad against 48-56 on the Mac, so the Pad's
  fastest core does about nine tenths of the Mac's work here.
- Ten minutes of a scripted game at the limit: 27 a second on average in
  every minute after the first, lowest interval 25, and no slowing as it
  warmed up.

## Phase 1 so far

`android/` builds a 6 MB APK (`./build-native.sh`, then
`./gradlew :app:assembleDebug`): a bare full-screen activity over the host
layer, with the Mac app's defaults. First run on the Pad, 2026-11-16,
started with a scripted route into level one:

- the core loads inside the app and the compiler's self-test passes there;
- OpenGL ES 3.2 on the Pad's Mali GPU, shaders installed, no GL errors;
- the display runs at 120 Hz and the game is stepped at 60 (600 frames
  drawn for 298 stepped);
- the picture is 2506x1880 in a 3008x1880 view, and the pixels read back
  from the screen are the game's;
- sound: a 32 kHz stream, with 31,900-32,150 frames a second delivered;
- level one at 29.3-29.6 frames a second, the same as the headless test.

Still for a person: how the picture looks, how the sound sounds, and a
controller. Xiaomi asks for a tap on the Pad for every install over adb.

To start it from a computer:
`adb shell am start -n gg.dekiru.nagori3k/.GameActivity` (add
`--ez perf true` for the performance log in logcat, tag `nagori3k`). It
plays `/sdcard/NUON/Tempest 3000.iso`, or the file named by `--es game`.

That is the first outcome in the table below, with the caveat that
drawing, sound and running as an app are not in the measurement yet.

Decisions (Jake, 2026-11-15): install as an APK; controller first, touch
later; the Pad first, phones later; Android 10 minimum.

The NDK (r27c) is on the external drive at
`/Volumes/External/dev/android-ndk`, because the Mac mini's internal disk
had 14 GB free.

## Goal

The Mac app's features on Android, on the Xiaomi Pad Mini first: library
from a folder with cover art, Tempest 3000 with the Arm64 compiler, the
same settings (analogue stick, trigger steering, weight and sensitivity,
frame rate limit, scanlines, stretch, high scores kept), button remapping,
and, new for Android, touch controls.

## What carries over, and what does not

Carries over unchanged:

- **The core** (`core/`). It already builds for Android with an OpenGL ES 3
  renderer, and every Tempest 3000 feature lives in it as a core option:
  the flywheel, spinner handling, frame limit, high scores, the controller
  message.
- **The host layer** (`host/nagori_host.cpp`). It is plain C++ with a C
  interface and already has the OpenGL ES include path. It needs a JNI
  wrapper and nothing else.

Has to be written again:

- **The app**: Kotlin, replacing the Swift app. Same screens, same
  settings, same defaults.
- **The platform glue**: an OpenGL ES surface and frame timing, sound
  output, controller input, file access.

New on Android:

- **Touch controls.** There is no keyboard and not always a controller.

## The question that decides everything: is the Pad fast enough?

On the M4 Pro, Tempest 3000 needs most of one fast core. Level one at the
30 frames-a-second limit uses about two thirds of what that core can
deliver, and the busiest scenes already fall slightly short. A tablet's
fastest core is slower than an M4's. So before any app work:

**Phase 0 is a measurement.** Build the core for Android, run the
differential test on the Pad to prove the compiler's output is right
there, then run Tempest 3000 with the performance log and read off how
many packets a second each processor gets and what frame rate the game
reaches in level one.

Three outcomes:

| Result on the Pad | What follows |
|---|---|
| Holds 30 in level one | Build the app as planned. |
| Holds 20-24 | Build it, with the frame limit defaulting to what it can hold. The game plays slower but evenly. |
| Well below 20 | Stop, and decide whether to spend the effort on emulator speed instead. |

I do not know the Pad's chip for certain and have not measured anything
on it, so I am not predicting which of these it will be.

## What I already know needs changing in the core

- **The temporary folder.** The core unpacks part of the disc image into
  `/tmp/nuance_XXXXXX`. Android has no `/tmp`, so as it stands no disc
  image would load. The folder has to come from the frontend (the app's
  cache directory).
- **Generated code.** The compiler asks for memory that is writable and
  executable. Android allows that for ordinary apps as far as I know, but
  it has only ever run on macOS; the core's start-up self-test will say.
- **File names from paths.** Saves and high scores are named after the
  game file. If the app has to hand the core a file descriptor rather than
  a path (see file access below), the name has to be passed separately.

## Phases

| # | Deliverable | Done when | Rough effort |
|---|---|---|---|
| 0 | Speed and correctness on the Pad | Differential test clean on the device; perf figures for level one; go/no-go | 1-2 days |
| 1 | The engine on Android | A bare activity plays Tempest 3000 from a file pushed to the Pad, with picture, sound and a controller | 3-5 days |
| 2 | Controls | Controller mapping with analogue triggers and stick; touch controls good enough to play without a controller | 4-6 days |
| 3 | The app | Library with cover art, settings, remapping screen, full screen; same defaults as the Mac | 5-7 days |
| 4 | Finish | Signed APK installed on the Pad; README and plan updated | 1-2 days |

Phase 0 needs the Android NDK installing on the Mac mini (the SDK is there,
the NDK is not) and the Pad connected over adb.

### Phase 1: the engine

- JNI wrapper over `nagori_host.h`.
- An OpenGL ES 3 surface. The game must be stepped 60 times a second
  whatever the display's refresh rate (many tablets run at 90 or 120 Hz),
  so frames are timed from the display's vsync and stepped at 60.
- Sound through the NDK's audio interface, pulling from the host layer's
  ring, at the core's 32 kHz.
- Pausing when the app goes to the background, and rebuilding the picture
  when Android takes the graphics context away.
- The core changes listed above.

### Phase 2: controls

- **Controllers:** buttons, left stick, and the analogue triggers for
  trigger steering, with the same remapping model as the Mac.
- **Touch:** this is the part to design properly, and it suits the game.
  A finger dragged across the screen is a spinner: it can feed the
  flywheel directly, the way the mouse does on the Mac. Proposed layout:
  drag anywhere on the left two thirds to turn the claw; fire, jump and
  superzapper buttons on the right; a small d-pad that appears for menus
  and name entry. Sensitivity and weight sliders apply to the drag.
- A setting to hide the touch controls when a controller is connected.

### Phase 3: the app

- **Library:** pick a folder once; list `.iso` and `.chd` in it and two
  levels down; cover art by the same rule as the Mac (a picture beside the
  game, else Wikipedia's cover for an exactly matching title).
- **Settings:** the same two groups, Game and Controls, same options and
  defaults (stick weight 70%, scanlines off, limit 30 or whatever phase 0
  says, high scores kept, controller message skipped).
- **Full screen** by default while playing, landscape.

## Decisions needed

1. **How it reaches the Pad.** Installing an APK directly (sideloading) is
   simplest and lets the app ask for access to all files, which makes the
   games folder straightforward. Going through Google Play means the
   restricted file picker instead, which the core's file handling does not
   fit without more work, and Play's own rules to meet. I suggest
   sideloading first.
2. **Touch-only play: a goal, or a fallback?** If you will always use a
   controller on the Pad, phase 2 shrinks to a few days and the touch
   layout can be basic.
3. **Phones as well as the Pad?** Phones are usually slower and the touch
   layout is tighter. I suggest the Pad only until it plays well.
4. **Minimum Android version.** The apps plan says Android 10. That still
   seems right.

## Risks

- **Speed**, as above. The largest risk, measured first.
- **Heat.** A tablet throttles its fastest core after some minutes under
  load. Phase 0 should run for ten minutes, not one.
- **The compiler on Android.** Only ever run on macOS. The differential
  test on the device is the check.
- **The OpenGL ES renderer.** It exists and upstream uses it, but I have
  never run it. Colours, the scanline shader and the frame buffer copy all
  need looking at on the device.
- **Sound latency and gaps.** Android's audio path differs from device to
  device.
- **Game loading from a network share.** Your games are on a share the
  Mac mounts. On the Pad they would need copying to its storage, about
  700 MB a game.
