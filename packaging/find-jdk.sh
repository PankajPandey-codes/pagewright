# Sourced from the repo root by run.sh and the build scripts. Makes JAVA_HOME point at a JDK 17+,
# using one already on the machine, or downloading one into .runtime/ if there is none.
# Set PAGEWRIGHT_IGNORE_SYSTEM_JAVA=1 to skip the machine's own Java (useful for testing the download path).

java_major() {
  "$1" -version 2>&1 | sed -n 's/.* version "\([0-9]*\).*/\1/p' | head -n 1
}

is_jdk17() {
  [ -x "$1/bin/java" ] && [ -x "$1/bin/javac" ] && [ "$(java_major "$1/bin/java")" -ge 17 ] 2>/dev/null
}

local_jdk() {
  for d in .runtime/jdk/Contents/Home .runtime/jdk; do
    if [ -x "$d/bin/java" ]; then
      (cd "$d" && pwd)
      return 0
    fi
  done
  return 1
}

FOUND=""
if [ -z "$PAGEWRIGHT_IGNORE_SYSTEM_JAVA" ]; then
  if [ -n "$JAVA_HOME" ] && is_jdk17 "$JAVA_HOME"; then
    FOUND="$JAVA_HOME"
  fi
  if [ -z "$FOUND" ] && [ -x /usr/libexec/java_home ]; then
    CANDIDATE="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
    if [ -n "$CANDIDATE" ] && is_jdk17 "$CANDIDATE"; then
      FOUND="$CANDIDATE"
    fi
  fi
  # On macOS, running the /usr/bin/java stub without a JDK pops up a dialog, so only probe PATH elsewhere.
  if [ -z "$FOUND" ] && [ "$(uname -s)" != "Darwin" ] && command -v java >/dev/null 2>&1; then
    CANDIDATE="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    if is_jdk17 "$CANDIDATE"; then
      FOUND="$CANDIDATE"
    fi
  fi
fi

if [ -z "$FOUND" ]; then
  LOCAL="$(local_jdk || true)"
  if [ -n "$LOCAL" ] && is_jdk17 "$LOCAL"; then
    FOUND="$LOCAL"
  fi
fi

if [ -z "$FOUND" ]; then
  sh packaging/fetch-jdk.sh || true
  LOCAL="$(local_jdk || true)"
  if [ -n "$LOCAL" ] && is_jdk17 "$LOCAL"; then
    FOUND="$LOCAL"
  fi
fi

if [ -z "$FOUND" ]; then
  echo "Could not find or download a JDK 17+. Install one (for example from https://adoptium.net) and try again." >&2
  exit 1
fi
JAVA_HOME="$FOUND"
export JAVA_HOME
