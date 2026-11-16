#!/bin/bash
# Builds the native half of the Android app with the NDK: the emulator core
# and the host layer with its JNI wrapper. Gradle then packages what this
# leaves in app/src/main/jniLibs. Usage: NDK=/path/to/android-ndk ./build-native.sh
set -euo pipefail
cd "$(dirname "$0")"
NDK="${NDK:-/Volumes/External/dev/android-ndk/android-ndk-r27c}"
[ -d "$NDK" ] || { echo "set NDK to the Android NDK's folder"; exit 1; }
out=app/src/main/jniLibs/arm64-v8a
mkdir -p "$out" build/core app/src/main/assets/system/nuance

cmake -S ../core -B build/core -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DBUILD_LIBRETRO=ON -DCMAKE_BUILD_TYPE=Release > build/core-configure.log 2>&1
cmake --build build/core --target nuance_libretro -j "$(sysctl -n hw.ncpu)" > build/core-build.log 2>&1 \
  || { tail -20 build/core-build.log; exit 1; }
cp build/core/nuance_libretro.so "$out/libnuance_libretro.so"

"$NDK"/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang++ \
  -std=c++17 -O2 -fPIC -shared -Wall -static-libstdc++ \
  app/src/main/cpp/nagori_jni.cpp ../host/nagori_host.cpp \
  -lGLESv3 -laaudio -llog -ldl -o "$out/libnagori.so"

# The core's own BIOS replacement files travel inside the app.
cp ../core/bios.cof ../core/minibios.cof ../core/minibiosX.cof app/src/main/assets/system/nuance/
ls -la "$out"
