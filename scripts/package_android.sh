#!/usr/bin/env bash
#
# One-click Android packaging for the Goutoujunshi fork.
#
# Reads the version from app/build.gradle.kts (single source of truth), runs the
# unit tests and assembles the debug APK, then copies the version-stamped APK
# into dist/ with a SHA256SUMS.txt beside it.
#
# Usage:
#   scripts/package_android.sh                 # test + build + package
#   scripts/package_android.sh --skip-tests    # faster: build + package only
#   scripts/package_android.sh --tag           # also create git tag v<version>
#   scripts/package_android.sh --clean         # remove dist/ first
#   scripts/package_android.sh --help
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK location (auto-detected if unset)
#   GRADLE_BIN                       Gradle executable to use (default: ./gradlew)
#   GRADLE_INIT                      Optional Gradle --init-script (mirror config)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/integrations/jev_android"
GRADLE_FILE="$ANDROID_DIR/app/build.gradle.kts"
DIST_DIR="$ROOT/dist"

SKIP_TESTS=0
MAKE_TAG=0
CLEAN=0

usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    --tag)        MAKE_TAG=1 ;;
    --clean)      CLEAN=1 ;;
    -h|--help)    usage ;;
    *) echo "unknown option: $arg" >&2; usage ;;
  esac
done

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- version
[ -f "$GRADLE_FILE" ] || fail "not found: $GRADLE_FILE"

VERSION_NAME="$(sed -nE 's/^val appVersionName = "([^"]+)".*/\1/p' "$GRADLE_FILE" | head -1)"
VERSION_CODE="$(sed -nE 's/^val appVersionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE" | head -1)"
[ -n "$VERSION_NAME" ] || fail "could not read appVersionName from $GRADLE_FILE"
[ -n "$VERSION_CODE" ] || fail "could not read appVersionCode from $GRADLE_FILE"

APK_NAME="goutoujunshi-jev-chat-${VERSION_NAME}-debug.apk"
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/$APK_NAME"

log "Packaging Goutoujunshi Jev Chat"
echo "    versionName : $VERSION_NAME"
echo "    versionCode : $VERSION_CODE"
echo "    output      : dist/$APK_NAME"

# ------------------------------------------------------------------- sdk
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for candidate in \
      "$HOME/Library/Android/sdk" \
      "$HOME/Android/Sdk" \
      "$HOME/Android/sdk" \
      "/usr/local/share/android-sdk"; do
    if [ -d "$candidate" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || fail \
  "Android SDK not found. Set ANDROID_HOME (e.g. export ANDROID_HOME=\$HOME/Library/Android/sdk)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
echo "    ANDROID_HOME: $ANDROID_HOME"

# The Android Gradle plugin reads sdk.dir from local.properties; keep it in sync
# so an IDE-free shell build finds the SDK too (the file is gitignored).
if [ ! -f "$ANDROID_DIR/local.properties" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ANDROID_DIR/local.properties"
  echo "    wrote integrations/jev_android/local.properties"
fi

# ---------------------------------------------------------------- gradle
if [ -n "${GRADLE_BIN:-}" ]; then
  GRADLE="$GRADLE_BIN"
elif [ -x "$ANDROID_DIR/gradlew" ]; then
  GRADLE="$ANDROID_DIR/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  fail "no Gradle found. Install Gradle, or run the project's ./gradlew once to bootstrap it."
fi

GRADLE_ARGS=(-p "$ANDROID_DIR" --no-daemon --console=plain)
if [ -z "${GRADLE_INIT:-}" ] && [ -f "$ROOT/scripts/gradle/mirrors.init.gradle" ]; then
  GRADLE_INIT="$ROOT/scripts/gradle/mirrors.init.gradle"
fi
[ -n "${GRADLE_INIT:-}" ] && { GRADLE_ARGS+=(-I "$GRADLE_INIT"); echo "    init script : $GRADLE_INIT"; }

# ----------------------------------------------------------------- clean
if [ "$CLEAN" -eq 1 ]; then
  log "Cleaning previous artifacts"
  rm -rf "$DIST_DIR"
fi

# ----------------------------------------------------------------- build
TASKS=()
[ "$SKIP_TESTS" -eq 0 ] && TASKS+=(":app:testDebugUnitTest")
TASKS+=(":app:assembleDebug")

log "Building (${TASKS[*]})"
if ! "$GRADLE" "${GRADLE_ARGS[@]}" "${TASKS[@]}"; then
  cat >&2 <<'HINT'

Gradle failed. If the error above mentions downloading a Gradle distribution
(e.g. "timeout" fetching gradle-8.9-bin.zip), the wrapper could not reach the CDN.

Options:
  1. Install Gradle 8.9+ and re-run with:  GRADLE_BIN="$(command -v gradle)" scripts/package_android.sh
  2. Configure a Gradle distribution mirror in integrations/jev_android/gradle/wrapper/gradle-wrapper.properties
  3. Pre-populate the wrapper cache by downloading the distribution manually.

HINT
  exit 1
fi

[ -f "$APK_PATH" ] || fail "expected APK not found: $APK_PATH"

# --------------------------------------------------------------- package
log "Publishing artifact to dist/"
mkdir -p "$DIST_DIR"
# Keep only the current release's APK so SHA256SUMS reflects this build.
find "$DIST_DIR" -maxdepth 1 -name '*.apk' ! -name "$APK_NAME" -delete 2>/dev/null || true
cp -f "$APK_PATH" "$DIST_DIR/$APK_NAME"

(
  cd "$DIST_DIR"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 *.apk > SHA256SUMS.txt
  else
    sha256sum *.apk > SHA256SUMS.txt
  fi
)

log "Done"
echo
echo "APK      : dist/$APK_NAME"
echo "Checksums: dist/SHA256SUMS.txt"
cat "$DIST_DIR/SHA256SUMS.txt"

# ------------------------------------------------------------------- tag
if [ "$MAKE_TAG" -eq 1 ]; then
  TAG="v$VERSION_NAME"
  if git -C "$ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "tag $TAG already exists; leaving it untouched"
  else
    git -C "$ROOT" tag -a "$TAG" -m "$TAG"
    echo "created tag $TAG (not pushed)"
  fi
fi
