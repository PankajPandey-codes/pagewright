#!/bin/sh
# Builds dist/"Pagewright.app" if it is missing or older than the sources, then opens it.
# Usage: packaging/start-mac.sh [file.pdf]
set -e
ORIGINAL_DIR="$(pwd)"
cd "$(dirname "$0")/.."
APP="dist/Pagewright.app"
if [ ! -d "$APP" ] || [ -n "$(find src pom.xml -newer "$APP/Contents/Info.plist" -print -quit)" ]; then
  echo "Building Pagewright (first launch only; this takes a few minutes)..."
  sh packaging/build-mac.sh
fi
if [ -n "$1" ]; then
  case "$1" in
    /*) FILE="$1" ;;
    *) FILE="$ORIGINAL_DIR/$1" ;;
  esac
  open -a "$APP" "$FILE"
else
  open "$APP"
fi
