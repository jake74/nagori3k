#!/bin/bash
# Builds the emulator core from core/ and the command-line host, into build/.
#   ./build.sh          then          build/nagori-cli <disc image>
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/system/nuance

cmake -S core -B build/core -DBUILD_LIBRETRO=ON -DCMAKE_BUILD_TYPE=Release > build/core-configure.log
cmake --build build/core --target nuance_libretro -j "$(sysctl -n hw.ncpu)" > build/core-build.log 2>&1 \
  || { tail -20 build/core-build.log; exit 1; }
cp build/core/nuance_libretro.dylib build/
# The core's own BIOS replacement files.
cp core/bios.cof core/minibios.cof core/minibiosX.cof build/system/nuance/

# The player is built as an application bundle: macOS only hands game
# controllers to programs that have a bundle identifier.
app=build/nagori3k.app/Contents
mkdir -p "$app/MacOS"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>gg.dekiru.nagori3k</string>
  <key>CFBundleName</key><string>nagori3k</string>
  <key>CFBundleExecutable</key><string>nagori3k</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>GCSupportsControllerUserInteraction</key><true/>
</dict></plist>
PLIST
clang++ -std=c++17 -O2 -fobjc-arc -Wall -Wno-deprecated-declarations \
  mac/nagori_cli.mm host/nagori_host.cpp \
  -framework Cocoa -framework OpenGL -framework GameController -framework AudioToolbox \
  -o "$app/MacOS/nagori3k"
# Run from a terminal through this, so that the log stays in the terminal.
printf '#!/bin/bash\nexec "$(dirname "$0")/nagori3k.app/Contents/MacOS/nagori3k" "$@"\n' > build/nagori-cli
chmod +x build/nagori-cli
echo "built build/nagori-cli"
