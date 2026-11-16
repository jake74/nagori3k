#!/bin/bash
# Makes the Mac app for other people: signed with a Developer ID, hardened,
# notarised by Apple, in a disk image. Run ./build.sh first.
#   mac/release.sh                 the real thing; needs the Developer ID
#                                  certificate in the keychain
#   mac/release.sh --local         signed for this Mac only and not sent to
#                                  Apple, to try the hardened app here
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" build/nagori3k.app/Contents/Info.plist)
out=build/release
app=$out/nagori3k.app
dmg=$out/nagori3k-$version.dmg

entitlements=mac/nagori3k.entitlements
if [ "${1:-}" = "--local" ]; then
  identity=-; stamp=--timestamp=none
  # Without a team in the signature, the hardened app refuses its own core;
  # the local build alone is told not to check. The real one must not need this.
  sed 's|</dict></plist>|  <key>com.apple.security.cs.disable-library-validation</key><true/>\
</dict></plist>|' mac/nagori3k.entitlements > build/local.entitlements
  entitlements=build/local.entitlements
else
  identity=$(security find-identity -v -p codesigning | sed -n 's/.*"\(Developer ID Application: [^"]*\)".*/\1/p' | head -1)
  [ -n "$identity" ] || { echo "no Developer ID Application certificate in the keychain"; exit 1; }
  stamp=--timestamp
fi

rm -rf "${out:?}"; mkdir -p "$out"
cp -R build/nagori3k.app "$app"
# Inside out: the core first, then the app with the right to generate code.
codesign --force --options runtime $stamp --sign "$identity" "$app/Contents/Frameworks/nuance_libretro.dylib"
codesign --force --options runtime $stamp --sign "$identity" --entitlements "$entitlements" "$app"
codesign --verify --strict --deep "$app"
echo "signed $app as: $identity"
[ "$identity" = - ] && exit 0

# The disk image: the app and a shortcut to Applications to drag it onto.
stage=$out/stage; mkdir -p "$stage"
cp -R "$app" "$stage/"; ln -s /Applications "$stage/Applications"
hdiutil create -quiet -volname "nagori3k" -srcfolder "$stage" -fs APFS -format ULFO "$dmg"
rm -rf "$stage"
codesign --force $stamp --sign "$identity" "$dmg"

# Apple checks it and issues a ticket, which is then attached to the image.
key=$(ls ~/.appstoreconnect/private_keys/AuthKey_*.p8 | head -1)
id=$(basename "$key" .p8); id=${id#AuthKey_}
: "${NOTARY_ISSUER:?set NOTARY_ISSUER to the App Store Connect issuer ID}"
xcrun notarytool submit "$dmg" --key "$key" --key-id "$id" --issuer "$NOTARY_ISSUER" --wait
xcrun stapler staple "$dmg"
spctl --assess --type open --context context:primary-signature -v "$dmg"
echo "made $dmg"
