#!/usr/bin/env bash
set -euo pipefail

LOG=/tmp/android-setup.log
exec > >(tee -a "$LOG") 2>&1

echo "=== Android SDK setup started ==="

SDK_ROOT=/opt/android-sdk

sudo mkdir -p "$SDK_ROOT"
sudo chown -R vscode:vscode "$SDK_ROOT"

cd /tmp

echo "Downloading commandline tools..."
curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdline-tools.zip

rm -rf "$SDK_ROOT/cmdline-tools"
mkdir -p "$SDK_ROOT/cmdline-tools/latest"
unzip -q cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools/latest"
mv "$SDK_ROOT/cmdline-tools/latest/cmdline-tools/"* "$SDK_ROOT/cmdline-tools/latest/"

yes | sdkmanager --licenses

sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0"

echo "=== Android SDK setup completed ==="#!/usr/bin/env bash
set -e

if ! command -v sleep >/dev/null 2>&1; then
  echo "ERROR: sleep command missing — wrong base image"
  exit 1
fi

SDK_ROOT=/opt/android-sdk
CMDLINE=$SDK_ROOT/cmdline-tools/latest

sudo mkdir -p $CMDLINE
sudo chown -R vscode:vscode /opt/android-sdk

cd /tmp

if [ ! -f commandlinetools.zip ]; then
  wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O commandlinetools.zip
fi

unzip -q commandlinetools.zip
mv cmdline-tools/* $CMDLINE

yes | sdkmanager --licenses

sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "cmake;3.22.1" \
  "ndk;26.1.10909125"
