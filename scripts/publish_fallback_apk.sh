#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

BUILD_APK=true
DRY_RUN=false
REMOTE_NAME="origin"
COMMIT_MESSAGE=""
SOURCE_APK="app/build/outputs/apk/debug/app-debug.apk"
DEST_APK="release-assets/agedm-tv-debug.apk"
SCRIPT_PATH="scripts/publish_fallback_apk.sh"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/publish_fallback_apk.sh [options]

Options:
  --skip-build            Do not run Gradle build; use existing APK
  --source <path>         Source APK path
  --message <message>     Commit message
  --remote <name>         Git remote name, default: origin
  --dry-run               Build/copy only, do not commit or push
  -h, --help              Show this help

Default behavior:
  1. Build debug APK
  2. Copy APK to release-assets/agedm-tv-debug.apk
  3. git add only:
     - release-assets/agedm-tv-debug.apk
     - scripts/publish_fallback_apk.sh
  4. Commit
  5. Push current branch
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)
      BUILD_APK=false
      shift
      ;;
    --source)
      SOURCE_APK="${2:?Missing value for --source}"
      shift 2
      ;;
    --message)
      COMMIT_MESSAGE="${2:?Missing value for --message}"
      shift 2
      ;;
    --remote)
      REMOTE_NAME="${2:?Missing value for --remote}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Current directory is not a git repository." >&2
  exit 1
fi

CURRENT_BRANCH="$(git branch --show-current)"
if [[ -z "$CURRENT_BRANCH" ]]; then
  echo "Detached HEAD is not supported by this script." >&2
  exit 1
fi

if ! git remote get-url "$REMOTE_NAME" >/dev/null 2>&1; then
  echo "Git remote '$REMOTE_NAME' does not exist." >&2
  exit 1
fi

ensure_local_toolchain() {
  if [[ -z "${JAVA_HOME:-}" && -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  fi

  if [[ -z "${ANDROID_SDK_ROOT:-}" && -d "/opt/homebrew/share/android-commandlinetools" ]]; then
    export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
  fi

  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
  fi
}

if [[ "$BUILD_APK" == true ]]; then
  ensure_local_toolchain
  chmod +x ./gradlew
  ./gradlew --no-daemon :app:assembleDebug
fi

if [[ ! -f "$SOURCE_APK" ]]; then
  echo "Source APK not found: $SOURCE_APK" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST_APK")"
cp "$SOURCE_APK" "$DEST_APK"

echo "Copied APK:"
echo "  from: $SOURCE_APK"
echo "  to:   $DEST_APK"

if [[ "$DRY_RUN" == true ]]; then
  echo
  echo "Dry run only. No commit or push executed."
  echo "Current branch: $CURRENT_BRANCH"
  exit 0
fi

git add "$DEST_APK"

if [[ -f "$SCRIPT_PATH" ]]; then
  git add "$SCRIPT_PATH"
fi

if git diff --cached --quiet -- "$DEST_APK" "$SCRIPT_PATH"; then
  echo "No staged changes for fallback APK/script. Nothing to commit."
  exit 0
fi

if [[ -z "$COMMIT_MESSAGE" ]]; then
  SHORT_SHA="$(git rev-parse --short HEAD)"
  COMMIT_MESSAGE="chore: update fallback apk (${SHORT_SHA})"
fi

git commit -m "$COMMIT_MESSAGE"
git push "$REMOTE_NAME" "HEAD:$CURRENT_BRANCH"

echo
echo "Done."
echo "Pushed branch '$CURRENT_BRANCH' to remote '$REMOTE_NAME'."
