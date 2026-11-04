#!/usr/bin/env bash
#
# build.sh — Build the "Here" app on Ubuntu without Google Play Services.
#
# Usage: bash build.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_HOME="${HOME}/Android/Sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_VERSION="8.9"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[✓]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
error() { echo -e "${RED}[✗]${NC} $*" >&2; }

# ─── Pre-flight ──────────────────────────────────────────────────────

if ! command -v java &>/dev/null; then
    error "Java is not installed."
    exit 1
fi

info "Java $(java -version 2>&1 | head -1)"

# ─── Android SDK ──────────────────────────────────────────────────────

if [[ -d "$ANDROID_HOME/cmdline-tools" ]]; then
    info "Android SDK already installed"
else
    info "Installing Android SDK..."
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    cd /tmp
    wget -q "$CMDLINE_TOOLS_URL" -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    if [[ -d cmdline-tools/cmdline-tools ]]; then
        mv cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/"
    else
        mv cmdline-tools "$ANDROID_HOME/cmdline-tools/"
    fi
    rm -rf cmdline-tools cmdline-tools.zip
    cd "$SCRIPT_DIR"
fi

export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "y" | sdkmanager "platforms;android-34" >/dev/null 2>&1 || true
echo "y" | sdkmanager "build-tools;34.0.0" >/dev/null 2>&1 || true
echo "y" | sdkmanager "platform-tools" >/dev/null 2>&1 || true

if [[ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]]; then
    echo "y" | sdkmanager --licenses >/dev/null 2>&1
fi

info "SDK ready"

# ─── Gradle ───────────────────────────────────────────────────────────

GRADLE_HOME="${HOME}/.gradle/gradle-${GRADLE_VERSION}"
if [[ ! -d "$GRADLE_HOME" ]]; then
    info "Installing Gradle ${GRADLE_VERSION}..."
    cd /tmp
    wget -q "$GRADLE_URL" -O gradle.zip
    mkdir -p "$GRADLE_HOME"
    unzip -q gradle.zip -d "$HOME/.gradle/"
    rm gradle.zip
    cd "$SCRIPT_DIR"
fi

export PATH="$GRADLE_HOME/bin:$PATH"

# ─── Build ────────────────────────────────────────────────────────────

cd "$SCRIPT_DIR"

# local.properties (if missing)
[[ -f "local.properties" ]] || echo "sdk.dir=${ANDROID_HOME}" > local.properties

# Gradle wrapper (if missing)
if [[ ! -f "./gradlew" ]]; then
    warn "Generating Gradle wrapper..."
    gradle wrapper --gradle-version "$GRADLE_VERSION" --no-daemon >/dev/null 2>&1
fi

info "Building..."
./gradlew assembleDebug --no-daemon 2>&1

# Move APK to a clean output directory (no buildType in path)
APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
    mkdir -p app/build/outputs
    cp "$APK" "app/build/outputs/hereHome.apk"
    info "Build successful!"
    info "APK: app/build/outputs/hereHome.apk"
    echo ""
    echo "Install on your device:"
    echo "  adb install app/build/outputs/hereHome.apk"
else
    error "Build failed — APK not found."
    exit 1
fi
