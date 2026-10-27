#!/bin/sh
# Downloads a JDK 17 (Eclipse Temurin) into .runtime/jdk, for machines that have no JDK 17+.
# Override the source with PAGEWRIGHT_JDK_URL=<url to a .tar.gz> (skips checksum verification).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$ROOT/.runtime"

case "$(uname -s)" in
  Darwin) API_OS=mac ;;
  Linux) API_OS=linux ;;
  *) echo "Automatic Java setup supports macOS and Linux only; install a JDK 17+ manually." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) API_ARCH=aarch64 ;;
  x86_64|amd64) API_ARCH=x64 ;;
  *) echo "Unsupported CPU architecture: $(uname -m)" >&2; exit 1 ;;
esac

API="https://api.adoptium.net/v3"
URL="${PAGEWRIGHT_JDK_URL:-$API/binary/latest/17/ga/$API_OS/$API_ARCH/jdk/hotspot/normal/eclipse}"

mkdir -p "$RUNTIME"
ARCHIVE="$RUNTIME/jdk-download.tar.gz"
echo "Downloading a Java runtime (one time only, about 190 MB)..." >&2
curl -fL --retry 3 -o "$ARCHIVE" "$URL" >&2

if [ -z "$PAGEWRIGHT_JDK_URL" ]; then
  EXPECTED="$(curl -fsSL "$API/assets/latest/17/hotspot?architecture=$API_ARCH&image_type=jdk&os=$API_OS&vendor=eclipse" \
    | tr -d '\n ' | sed -n 's/.*"package":{[^}]*"checksum":"\([0-9a-f]\{64\}\)".*/\1/p' | head -n 1)"
  if command -v shasum >/dev/null 2>&1; then
    ACTUAL="$(shasum -a 256 "$ARCHIVE" | cut -d' ' -f1)"
  else
    ACTUAL="$(sha256sum "$ARCHIVE" | cut -d' ' -f1)"
  fi
  if [ -z "$EXPECTED" ] || [ "$EXPECTED" != "$ACTUAL" ]; then
    rm -f "$ARCHIVE"
    echo "Downloaded Java did not match its published checksum; aborting." >&2
    exit 1
  fi
fi

# Extract beside the final location and move into place, so an interrupted run never leaves a half-built JDK.
STAGE="$RUNTIME/jdk.new"
rm -rf "$STAGE"
mkdir -p "$STAGE"
tar -xzf "$ARCHIVE" -C "$STAGE" --strip-components=1
rm -rf "$RUNTIME/jdk"
mv "$STAGE" "$RUNTIME/jdk"
rm -f "$ARCHIVE"
echo "Java installed in $RUNTIME/jdk" >&2
