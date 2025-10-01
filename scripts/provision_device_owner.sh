#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.laurelid"
ADMIN_COMPONENT="com.laurelid/.DeviceAdminReceiver"
LAUNCHER_ACTIVITY="com.laurelid/.ui.ScannerActivity"
ADB_BIN="${ADB:-adb}"

echo "Using ADB binary: ${ADB_BIN}" >&2

run_adb() {
  echo "+ ${ADB_BIN} $*" >&2
  "${ADB_BIN}" "$@"
}

run_adb wait-for-device

# Ensure the target package is installed before we proceed.
if ! run_adb shell pm list packages | grep -q "${PACKAGE}"; then
  echo "Error: ${PACKAGE} is not installed on the connected device" >&2
  exit 1
fi

# Promote the app to device owner so lock task and policy APIs are available.
run_adb shell dpm set-device-owner "${ADMIN_COMPONENT}"

# Allow the kiosk app to enter lock task (screen pinning) mode automatically.
run_adb shell dpm set-lock-task-packages ${PACKAGE} ${PACKAGE}

# Whitelist system surfaces/dialogs needed for camera prompts, intents, etc.
run_adb shell dpm set-lock-task-features ${PACKAGE} KEYGUARD SYSTEM_INFO NOTIFICATIONS GLOBAL_ACTIONS

# Ensure Android treats the scanner activity as the launcher so it boots on startup.
run_adb shell cmd package set-home-activity "${LAUNCHER_ACTIVITY}"

# Allow showing overlay dialogs (camera permission, play services updates, etc.).
run_adb shell appops set ${PACKAGE} SYSTEM_ALERT_WINDOW allow
run_adb shell appops set ${PACKAGE} WAKE_LOCK allow

# Avoid aggressive doze/standby so the kiosk stays responsive to NFC intents.
run_adb shell cmd deviceidle whitelist +${PACKAGE}
run_adb shell am set-inactive ${PACKAGE} false

# Make sure the initial setup wizard is considered complete to avoid interruptions.
run_adb shell settings put global device_provisioned 1
run_adb shell settings put secure user_setup_complete 1

echo "\nProvisioning complete. Rebooting device to confirm auto-start behavior..." >&2
run_adb reboot
