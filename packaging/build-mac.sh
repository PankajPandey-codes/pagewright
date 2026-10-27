#!/bin/sh
# Builds dist/"Pagewright.app" (self-contained: bundles its own Java runtime). Needs a JDK 17+ (Maven is provided by ./mvnw).
set -e
cd "$(dirname "$0")/.."
APP_NAME="Pagewright"
. packaging/find-jdk.sh
./mvnw -q clean package

ICONSET=target/icon.iconset
mkdir -p "$ICONSET"
for s in 16 32 64 128 256 512; do
  sips -z $s $s src/main/resources/icon.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) src/main/resources/icon.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o target/icon.icns

mkdir -p target/input && cp target/pagewright.jar target/input/
TYPE="${1:-app-image}"
mkdir -p dist
if [ "$TYPE" = "app-image" ]; then
  rm -rf "dist/$APP_NAME.app"
else
  rm -f "dist/$APP_NAME-"*."$TYPE"
fi
"$JAVA_HOME/bin/jpackage" \
  --type "$TYPE" \
  --name "$APP_NAME" \
  --app-version 1.0.0 \
  --input target/input \
  --main-jar pagewright.jar \
  --main-class pagewright.App \
  --icon target/icon.icns \
  --dest dist \
  --add-modules java.desktop,java.logging,java.xml,java.naming,jdk.unsupported \
  --java-options "-Xmx2g" \
  --mac-package-identifier io.github.pankajpandeycodes.pagewright \
  --file-associations packaging/pdf-association.properties
echo "Built: $(ls dist)"
