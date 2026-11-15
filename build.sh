#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
variant="${1:-debug}"
case "$variant" in
  debug) task=:app:assembleDebug ;;
  release) task=:app:assembleRelease ;;
  *) echo 'Usage: ./build.sh [debug|release]' >&2; exit 2 ;;
esac
# Keep build caches inside this checkout. An installed Gradle may be supplied explicitly.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$PWD/.android-home}"
if [ -n "${GDEBYL_GRADLE_BIN:-}" ]; then
    exec "$GDEBYL_GRADLE_BIN" "$task"
fi
exec ./gradlew "$task"
