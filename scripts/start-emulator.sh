#!/usr/bin/env bash
set -euo pipefail
IMAGE="${1:-system-images;android-35;google_apis;x86_64}"
echo "y" | avdmanager create avd -n ci_api35 -k "${IMAGE}" --device "pixel_5" || true
"$ANDROID_HOME"/emulator/emulator -avd ci_api35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -camera-back none -camera-front none -accel on -netfast -idle-gravity 90 >/dev/null 2>&1 &
BOOT_OK=""
for i in {1..60}; do
  if adb wait-for-device shell getprop sys.boot_completed 2>/dev/null | grep -q "1"; then
    BOOT_OK="1"
    break
  fi
  echo "Waiting for emulator to boot... ($i/60)"
  sleep 5
done
if [ -z "$BOOT_OK" ]; then
  echo "Emulator failed to boot"
  exit 1
fi
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

