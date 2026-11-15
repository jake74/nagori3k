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

clang++ -std=c++17 -O2 -fobjc-arc -Wall -Wno-deprecated-declarations \
  mac/nagori_cli.mm host/nagori_host.cpp \
  -framework Cocoa -framework OpenGL -framework GameController -framework AudioToolbox \
  -o build/nagori-cli
echo "built build/nagori-cli"
