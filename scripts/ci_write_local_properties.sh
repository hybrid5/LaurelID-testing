#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
mkdir -p "$SDK_DIR"
echo "sdk.dir=$SDK_DIR" > "$ROOT/local.properties"
echo "[ok] local.properties -> $SDK_DIR"
