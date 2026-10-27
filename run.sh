#!/bin/sh
# Starts Pagewright from source: ./run.sh [file.pdf]
# Sets itself up on first run (finds or downloads Java, builds the app). ./run.sh --prepare only does the setup.
set -e
ORIGINAL_DIR="$(pwd)"
cd "$(dirname "$0")"
. packaging/find-jdk.sh
if [ ! -f target/pagewright.jar ] || [ -n "$(find src pom.xml -newer target/pagewright.jar -print -quit)" ]; then
  echo "Building Pagewright (the first time takes a minute or two)..."
  ./mvnw -q package
fi
if [ "$1" = "--prepare" ]; then
  exit 0
fi
# The script runs from the repo folder, so make a relative file argument absolute first.
if [ -n "$1" ]; then
  case "$1" in
    /*) ;;
    *) set -- "$ORIGINAL_DIR/$1" ;;
  esac
fi
DOCK_OPT=""
[ "$(uname)" = "Darwin" ] && DOCK_OPT="-Xdock:name=Pagewright"
exec "$JAVA_HOME/bin/java" -Xmx2g ${DOCK_OPT:+"$DOCK_OPT"} -jar target/pagewright.jar "$@"
