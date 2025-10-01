#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_PATH="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "${SDK_PATH}" ]]; then
  echo "Error: ANDROID_SDK_ROOT or ANDROID_HOME must be set" >&2
  exit 1
fi

cat > "${PROJECT_ROOT}/local.properties" <<PROPS
sdk.dir=${SDK_PATH}
PROPS

echo "Wrote local.properties pointing to ${SDK_PATH}" >&2
