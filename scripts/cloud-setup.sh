#!/usr/bin/env bash
# Cloud-session setup for mootmaker-android: Android SDK + Gradle warm-up.
# Paste this into the cloud environment's setup script. Idempotent; debug builds only.
set -euo pipefail

CMDLINE_TOOLS_VERSION=13114758
SDK_ROOT="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME="$SDK_ROOT" ANDROID_SDK_ROOT="$SDK_ROOT"
start=$(date +%s)

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK_ROOT/cmdline-tools"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null

# Persist for later shells in the session.
for f in "$HOME/.bashrc"; do
  grep -q 'ANDROID_HOME=' "$f" 2>/dev/null || \
    printf 'export ANDROID_HOME=%s\nexport ANDROID_SDK_ROOT=%s\n' "$SDK_ROOT" "$SDK_ROOT" >> "$f"
done

# Gradle warm-up: resolve dependencies so the first real build is fast.
# Runs only if the repo is checked out where the setup script runs. Not fatal: Maven Central can
# answer 429 through the cloud proxy, and a later build simply retries.
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$repo/gradlew" ]; then
  warmed=no
  for attempt in 1 2 3 4 5; do
    if (cd "$repo" && ./gradlew --no-daemon --max-workers=1 -q \
          :app:assembleDebug :app:assembleDebugUnitTest :data:assembleDebugUnitTest :app:lintDebug); then
      warmed=yes
      break
    fi
    echo "Gradle warm-up attempt $attempt failed; retrying in 20s"
    sleep 20
  done
  [ "$warmed" = yes ] || echo "warm-up incomplete; the first build will finish it"
fi

echo "cloud-setup.sh finished in $(( $(date +%s) - start ))s"
