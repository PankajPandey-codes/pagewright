#!/bin/sh
# Rebuilds the double-click launcher "Start Pagewright.app" at the repo root from launcher.applescript.
# It is an AppleScript application so macOS can ask the user for access to Desktop/Documents folders.
set -e
cd "$(dirname "$0")/.."
APP="Start Pagewright.app"
PLIST="$APP/Contents/Info.plist"
PB=/usr/libexec/PlistBuddy

rm -rf "$APP"
osacompile -o "$APP" packaging/launcher.applescript

ICON_NAME="$($PB -c 'Print :CFBundleIconFile' "$PLIST")"
ICONSET="$(mktemp -d)/icon.iconset"
mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z $s $s src/main/resources/icon.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) src/main/resources/icon.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/$ICON_NAME.icns"

# osacompile ships a placeholder icon catalog that would take priority over our icns file.
rm -f "$APP/Contents/Resources/Assets.car"
$PB -c 'Delete :CFBundleIconName' "$PLIST" 2>/dev/null || true
$PB -c 'Set :CFBundleName "Pagewright"' "$PLIST"
$PB -c 'Add :CFBundleIdentifier string io.github.pankajpandeycodes.pagewright.launcher' "$PLIST"
codesign --force --deep -s - "$APP"
echo "Built $APP"
