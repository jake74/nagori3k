#!/bin/bash
# Builds the emulator core from core/ and the command-line host, into build/.
#   ./build.sh          then          build/nagori-cli <disc image>
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/system/nuance

cmake -S core -B build/core -DBUILD_LIBRETRO=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 > build/core-configure.log
cmake --build build/core --target nuance_libretro -j "$(sysctl -n hw.ncpu)" > build/core-build.log 2>&1 \
  || { tail -20 build/core-build.log; exit 1; }
cp build/core/nuance_libretro.dylib build/
# The core's own BIOS replacement files.
cp core/bios.cof core/minibios.cof core/minibiosX.cof build/system/nuance/

# --- The command-line player. An application bundle, because macOS only
# hands game controllers to programs that have a bundle identifier.
plist() { # plist <file> <identifier> <name> <executable>
cat > "$1" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>$2</string>
  <key>CFBundleName</key><string>$3</string>
  <key>CFBundleDisplayName</key><string>$3</string>
  <key>CFBundleExecutable</key><string>$4</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.3</string>
  <key>CFBundleVersion</key><string>4</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.games</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>GCSupportsControllerUserInteraction</key><true/>
</dict></plist>
PLIST
}
frameworks="-framework Cocoa -framework OpenGL -framework GameController -framework AudioToolbox"

cli=build/cli/nagori3k-cli.app/Contents
mkdir -p "$cli/MacOS"
plist "$cli/Info.plist" gg.dekiru.nagori3k.cli "nagori3k cli" nagori3k-cli
clang++ -std=c++17 -O2 -fobjc-arc -Wall -Wno-deprecated-declarations \
  mac/nagori_cli.mm host/nagori_host.cpp $frameworks -o "$cli/MacOS/nagori3k-cli"
# Run from a terminal through this, so that the log stays in the terminal.
printf '#!/bin/bash\nexec "$(dirname "$0")/cli/nagori3k-cli.app/Contents/MacOS/nagori3k-cli" "$@"\n' > build/nagori-cli
chmod +x build/nagori-cli
echo "built build/nagori-cli"

# --- The app: Swift on top of the same host layer, with the core and its
# BIOS files inside the bundle.
app=build/nagori3k.app/Contents
rm -rf build/nagori3k.app
mkdir -p "$app/MacOS" "$app/Frameworks" "$app/Resources/system/nuance" build/obj
plist "$app/Info.plist" gg.dekiru.nagori3k nagori3k nagori3k
cp build/nuance_libretro.dylib "$app/Frameworks/"
# The icon is made from art/nagori3k.svg by tools/make-icons.swift.
cp mac/AppIcon.icns "$app/Resources/"
cp core/bios.cof core/minibios.cof core/minibiosX.cof "$app/Resources/system/nuance/"
clang++ -std=c++17 -O2 -mmacosx-version-min=14.0 -fobjc-arc -Wall -Wno-deprecated-declarations -c mac/App/NagoriGameView.mm -o build/obj/NagoriGameView.o
clang++ -std=c++17 -O2 -mmacosx-version-min=14.0 -Wall -Wno-deprecated-declarations -c host/nagori_host.cpp -o build/obj/nagori_host.o
swiftc -O -parse-as-library -target arm64-apple-macos14.0 -Xcc -Wno-deprecated-declarations -suppress-warnings -import-objc-header mac/App/Bridging.h \
  mac/App/*.swift build/obj/NagoriGameView.o build/obj/nagori_host.o \
  $frameworks -framework SwiftUI -framework Carbon -lc++ -o "$app/MacOS/nagori3k"
# Signed for this Mac only ("ad hoc"), without the hardened runtime, so the
# core may generate code. A build for other people needs a real signature
# and the allow-jit entitlement.
codesign --force --sign - "$app/Frameworks/nuance_libretro.dylib" 2>/dev/null
codesign --force --sign - build/nagori3k.app 2>/dev/null
echo "built build/nagori3k.app"
