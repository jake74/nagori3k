# Where things stand

Written 2026-11-16, at the end of the first long working session, for
whoever picks this up next. The detailed history is in
[android-plan.md](android-plan.md) and, in the core fork,
`core/docs/apps-plan.md` and `core/docs/arm64-jit-plan.md`.

## What works

- **Mac app** (`mac/App`, built by `./build.sh` into `build/nagori3k.app`):
  library with cover art, play in a window or full screen, Options in two
  tabs (Game, Controls), controller and keyboard mapping, analogue stick
  and trigger steering, frame rate limit and counter, stretch, high scores
  kept. macOS 14 or later, Apple silicon, signed for the build machine
  only.
- **Android app** (`android/`, `./build-native.sh` then
  `./gradlew :app:assembleDebug`): the same, plus a pause menu (tap, Back,
  or Start and Select together) from which Options can be reached during a
  game. Tested on a Xiaomi Pad Mini, Android 16. Android 10 minimum, Arm64.
- **Tempest 3000** runs at the original's speed on both, at 30 frames a
  second in ordinary play. The busiest scenes dip to roughly 18-26.

## Open when the session ended

1. **A crash was reported on the Mac** shortly after the build that makes
   games open from black instead of magenta (core `90c3aa2`). The only
   crash report from that time is for the command-line player
   (`nagori3k-cli-2026-11-16-072614.ips`), from a test run with the
   performance log on: at exit the host layer unloaded the core while the
   core's sampling thread was still running. The host layer no longer
   unloads it. There is no crash report for the app itself, so the dialog
   seen was most likely this one, but that is an inference: if the app
   crashes again, look for `nagori3k-*.ips` (without `-cli`) in
   `~/Library/Logs/DiagnosticReports/`. **Check this first.**
2. **The magenta fix on the Pad** was installed but not yet confirmed by
   eye.
3. **Not yet tried by a person:**
   - Android: the pause menu from the controller, moving around the menus
     with the controller, the sliders on the Controls tab.
   - Mac: the layout of the Game and Controls tabs (they cannot be drawn to
     an image file for checking), capturing a button on the mapping screen,
     switching from one game to another.
4. **Speed in the busiest scenes** is parked. The measurements and the
   experiments that did not help are in `core/docs/arm64-jit-plan.md`,
   section "Speed in busy scenes". The remaining route is several days of
   work in the core.

## Not started

- An app icon.
- Builds for other people: an Xcode project, Developer ID signing with the
  JIT entitlement and notarisation for the Mac; a release-signed APK with
  its own key for Android.
- Touch controls and phones (put off deliberately).
- Other games. Only `.iso` and `.chd` load; `.mdf` and `.zip` do not.
- A licence for the code in this repository. It is private until there is
  one. The core's licence forbids redistribution for profit, and the app
  is to stay free.
- iOS: dropped, because apps there cannot generate code and Tempest 3000
  needs that to run at speed.

## Things that are easy to get wrong

- **Updating the core.** After pushing a change in the fork:
  `cd core && git fetch origin && git merge --ff-only origin/macos-arm64`,
  then look at `git -C core log -1` before building. This step once failed
  without saying so, and both apps shipped a frame rate counter that read 0.
- **Installing on the Xiaomi Pad** needs a tap on the Pad for every
  `adb install`. Its wireless debugging port changes between sessions.
- **The Mac app's test options** must come in pairs (`--play <file>`,
  `--quit-after <seconds>`, `--full-screen yes`, `--report yes`,
  `--snapshot <folder>`). A word left over is taken by AppKit as a file to
  open, and then no window appears.
- **Enter initials with the d-pad**, not the stick. The README explains
  why.
- The NDK is on the external drive (`/Volumes/External/dev/android-ndk`)
  because the build machine's internal disk is nearly full.
