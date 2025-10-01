# Device Owner Provisioning & Recovery

This project ships with an ADB helper script that promotes the kiosk build to a
**device owner**, configures lock task mode, whitelists required system dialogs,
and makes the scanner activity the default launcher so the app starts on boot.
Follow the steps below on a dedicated kiosk profile or freshly factory-reset
device.

## Prerequisites

1. **Factory reset or create a new work profile.** Android only allows setting a
   device owner on a clean user profile.
2. **Complete the initial setup wizard** until you reach the launcher.
3. **Enable developer options and USB debugging** (Settings → About → tap Build
   number seven times, then Settings → System → Developer options).
4. Install the LaurelID APK and connect the device to your workstation via USB.
5. Verify `adb devices` shows the target hardware. The script will block until a
   device is present.

## Automated Provisioning

Run the helper script from the repository root:

```bash
./scripts/provision_device_owner.sh
```

The script performs the following configuration:

- Promotes `com.laurelid/.DeviceAdminReceiver` to **device owner**.
- Adds the app to the lock-task allowlist and enables key system dialogs
  (`KEYGUARD`, `SYSTEM_INFO`, `NOTIFICATIONS`, `GLOBAL_ACTIONS`) so permission
  prompts and error sheets can still surface while pinned.
- Sets `com.laurelid/.ui.ScannerActivity` as the **default home/launcher** to
  auto-start the kiosk UI on boot.
- Grants overlay-style dialogs (camera permission, Play Services update sheets)
  and relaxes doze/standby policies so NFC intents continue to wake the device.
- Marks the setup wizard as complete to avoid it reappearing on reboot, then
  reboots the device for verification.

If you installed ADB in a non-standard location, set the `ADB` environment
variable to override the binary used by the script:

```bash
ADB=/path/to/platform-tools/adb ./scripts/provision_device_owner.sh
```

## Verification Checklist

After the device reboots:

1. The LaurelID scanner should appear automatically without visiting the system
   launcher.
2. Attempt to leave lock task mode by pressing Back/Home—Android should display
   the pinned-task dialog.
3. Trigger a permission prompt (e.g., clear the camera permission and restart).
   The dialog should appear despite lock task being active.
4. Allow the device to idle for several minutes; it should remain responsive to
   NFC taps or manual refreshes.

## Uninstall / Reset Instructions

If you need to reclaim the device or test from a clean state, run the following
ADB commands:

```bash
adb wait-for-device
adb shell dpm remove-active-admin --user 0 "com.laurelid/.DeviceAdminReceiver"
adb shell cmd package clear-home-activity
adb shell cmd deviceidle whitelist -com.laurelid
adb shell am set-inactive com.laurelid true
adb shell appops set com.laurelid SYSTEM_ALERT_WINDOW default
adb shell appops set com.laurelid WAKE_LOCK default
adb uninstall com.laurelid
```

> ⚠️ Removing a device owner may fail if other policies are still active. A
> factory reset is the guaranteed fallback: `adb shell recovery --wipe_data` or
> use the stock recovery menu.

After removal, you can safely provision a different device owner or return the
hardware to general-purpose use.
