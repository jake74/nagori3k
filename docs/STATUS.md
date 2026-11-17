# Where things stand

Written 2026-11-16, at the end of the first long working session, and
kept up since (last on 2026-11-17, at release 0.2), for whoever picks
this up next. The detailed history is in
[android-plan.md](android-plan.md) and, in the core fork,
`core/docs/apps-plan.md` and `core/docs/arm64-jit-plan.md`.

## What works

- **Mac app** (`mac/App`, built by `./build.sh` into `build/nagori3k.app`):
  library with cover art, play in a window or full screen, Options in two
  tabs (Game, Controls), controller and keyboard mapping, analogue stick
  and trigger steering, frame rate limit and counter, stretch, high scores
  kept. macOS 14 or later, Apple silicon.
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
2. **Confirmed on the Pad** (2026-11-16, build `b27a3a7`): games open from
   black with no magenta flash, the pause menu works, the icon looks right.
3. **Not yet tried by a person:**
   - Android: the sliders on the Controls tab.
   - Mac: the icon in the Dock, the layout of the Game and Controls tabs
     (they cannot be drawn to an image file for checking), capturing a
     button on the mapping screen, switching from one game to another.
4. **Speed in the busiest scenes** is parked. The measurements and the
   experiments that did not help are in `core/docs/arm64-jit-plan.md`,
   section "Speed in busy scenes". The remaining route is several days of
   work in the core.

## Touch controls

Android only, first version (2026-11-17). Checked on the Pad with simulated
taps and swipes: the controls draw, A, B, the d-pad and the Menu button
register, a swipe turns the claw and a flick coasts. **Not yet checked:**
real fingers (several at once), how it feels, hiding when a controller is
used, auto-fire, the Options group's layout, and any phone. `TouchControls.kt` is a single view over
the game that draws and handles every touch itself (one finger per pointer
id), sending straight to `Native`. Dragging in the left half, below the top
strip, calls `Native.addSpinner` (the host's `nagori_add_spinner`, as the Mac
mouse does); a flick coasts, using the Weight setting (`flickKeep`). Buttons
(A, B, Start, Menu) are one list in the file; the d-pad is separate. The core
already applies "reverse direction" to spinner counts, so the app does not.
Settings keys: `touchControls` ("auto", "always", "never") and `autoFire`.
Auto shows the controls when no controller is connected, hides them on a
controller key or a real stick push, and shows them again on a tap.
The calibration constant is `SPIN_COUNTS_PER_DP` (1.2) at the top of
`TouchControls.kt`, with the reasoning. Log lines (tag `nagori3k`, debug):
`touch: show (...)` / `touch: hide (...)`, `touch: <button> down` / `up`,
and `touch: spin <counts in the last second>` (absolute sum).

## The logo and icons

The logo is `art/nagori3k.svg` (Jake's design, a square on black). Both
app icons and the README image are made from it by
`swift tools/make-icons.swift`, run from the repository root; the results
are committed, so this is only needed when the logo changes.

## Signed Mac build

`./build.sh`, then `NOTARY_ISSUER=<App Store Connect issuer ID>
mac/release.sh`, makes `build/release/nagori3k-<version>.dmg`: signed with
the Developer ID certificate (in its own keychain on the build machine),
hardened, with the one entitlement that lets the core generate code,
notarised and stapled. `mac/release.sh --local` makes a hardened build for
trying on the build machine only. The first submission to Apple (0.1) took
most of a day to clear; 0.1.1 took a few minutes. The 0.1.1 disk image has been
opened and run on a second Mac (2026-11-17).

## Update check

Both apps ask GitHub once, when they open and at most once in 24 hours,
for `https://api.github.com/repos/jake74/nagori3k/releases/latest` (only
`User-Agent: nagori3k/<version>` goes with it). If its `tag_name` is a
newer version than the app's own, the library shows a banner (Download
opens `html_url`; What's new shows `body`; Skip this version remembers it).
Nothing is downloaded. Options, Game tab, "Updates": a switch (on by
default), "Check now" (ignores the limit and the switch, and clears a
skip) and the version. Any failure is silent. Code: `mac/App/Updates.swift`
and `android/.../Updates.kt`; settings keys `checkForUpdates`,
`skippedUpdateVersion`, `lastUpdateCheck`.

Test hooks: Mac `--update-url <address>`, Android intent string extra
`updateUrl` (file:// address or plain path as well as https://). Either
replaces the GitHub address and forces a check at launch. Each check logs
one line, `nagori3k: update: available|up to date|skipped|check failed ...`
(standard error on the Mac, logcat tag `nagori3k` on Android).
`./build/nagori3k.app/Contents/MacOS/nagori3k --update-url file:///x.json --quit-after 6`
is enough to try it with a hand-written JSON file.

The repository is public (since 2026-11-17), so the real address answers.
If it ever stops answering (404, no network), nothing is shown.

## Releases

Release 0.2 (2026-11-17) is the latest on the repository's releases page:
the Mac disk image, the Android APK and the RetroArch core for Apple
silicon Macs with its install notes. It is the first whose apps check for
updates, so it is the first that will tell people about its successor;
0.1.1 before it does not. (0.1 was withdrawn for two scanline faults.)

To make the next one: raise the version in `build.sh` (both plist keys)
and `android/app/build.gradle.kts` (`versionCode` and `versionName`),
build both, run `mac/release.sh`, zip the core as before (the dylib, its
`.info`, the BIOS files and `nuance.cfg` under `system/nuance`, the core's
licence and `INSTALL.txt`), and create the release with `gh`.

## Signed Android build

`cd android && ./gradlew :app:assembleRelease` makes
`app/build/outputs/apk/release/app-release.apk`, signed with nagori3k's own
key (made 2026-11-16). The key is `~/.android-keys/nagori3k-release.jks` on
the build machine, alias `nagori3k`; its password is in the macOS Keychain
under "nagori3k Android release key". Neither is in this repository, and
without them the release build comes out unsigned. **Back the key up:** an
app can only be updated by a build signed with the same key.

The signed build has been made and its signature checked, but not yet run
on a device. It cannot be installed over the debug build: that one has to
be removed first, which removes its settings and high scores.

## Licence

Decided 2026-11-16: the code here is 3-Clause BSD, Copyright Jake Smith
(`LICENSE`), matching the emulator contributors' licence. The core keeps
its own terms. The project's rule is no ads, no selling, no profit. The
repository was made public on 2026-11-17.

## Not started

- Touch controls and phones (put off deliberately).
- Other games. Only `.iso` and `.chd` load; `.mdf` and `.zip` do not.
- iOS: dropped, because apps there cannot generate code and Tempest 3000
  needs that to run at speed.

## Things that are easy to get wrong

- **Updating the core.** After pushing a change in the fork:
  `cd core && git fetch origin && git merge --ff-only origin/macos-arm64`,
  then look at `git -C core log -1` before building. This step once failed
  without saying so, and both apps shipped a frame rate counter that read 0.
- **Installing on the Xiaomi Pad** needs a tap on the Pad for every
  `adb install`. Its wireless debugging port changes between sessions, and
  it can forget the pairing: then `adb connect` fails although the port is
  open. Pair again with a code from the Pad (`adb mdns services` shows the
  pairing port; the code expires quickly).
- **The Mac app's test options** must come in pairs (`--play <file>`,
  `--quit-after <seconds>`, `--full-screen yes`, `--report yes`,
  `--snapshot <folder>`). A word left over is taken by AppKit as a file to
  open, and then no window appears.
- **Enter initials with the d-pad**, not the stick. The README explains
  why.
- The NDK is on the external drive (`/Volumes/External/dev/android-ndk`)
  because the build machine's internal disk is nearly full.
