#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

OUT="$PROJECT_DIR/release-assets"
DEBUG_SRC="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_SRC="app/build/outputs/apk/release/app-release.apk"
DEBUG_DST="$OUT/agedm-tv-debug.apk"
RELEASE_DST="$OUT/agedm-tv-release.apk"

BUILD_DEBUG=true
BUILD_RELEASE=true

for arg in "$@"; do
  case "$arg" in
    --debug-only)   BUILD_RELEASE=false ;;
    --release-only) BUILD_DEBUG=false ;;
    --help|-h)
      echo "Usage: $0 [--debug-only | --release-only]"
      exit 0
      ;;
  esac
done

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  AGE DM TV — APK Builder"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

TASKS=""
$BUILD_DEBUG   && TASKS="$TASKS assembleDebug"
$BUILD_RELEASE && TASKS="$TASKS assembleRelease"

echo "Tasks:$TASKS"
echo ""

START=$(date +%s)
./gradlew $TASKS
END=$(date +%s)

mkdir -p "$OUT"

if $BUILD_DEBUG && [[ -f "$DEBUG_SRC" ]]; then
  cp "$DEBUG_SRC" "$DEBUG_DST"
fi

if $BUILD_RELEASE && [[ -f "$RELEASE_SRC" ]]; then
  cp "$RELEASE_SRC" "$RELEASE_DST"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Build complete in $((END - START))s"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ls -lh "$OUT"/*.apk 2>/dev/null || true
