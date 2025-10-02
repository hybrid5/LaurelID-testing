# LaurelID Kiosk Mode Architecture

This document explains how the Android client enforces kiosk behavior when it
is promoted to **device owner**.

## Device Owner Receiver

* `com.laurelid.kiosk.LaurelIdDeviceAdminReceiver` is registered in the
  manifest as a `DeviceAdminReceiver`. It only logs lifecycle callbacks but is
  required so that Android accepts the app as a device owner.
* A companion XML descriptor (`res/xml/device_admin_receiver.xml`) advertises
  the admin policies the app may request (lock task, password reset, and wipe).

## Policy Bootstrap

* `LaurelIdApp` calls `KioskDeviceOwnerManager.enforcePolicies()` during app
  start-up.
* The helper checks `DevicePolicyManager.isDeviceOwnerApp()`. Release builds
  throw an `IllegalStateException` if the app is *not* the active device owner,
  preventing misconfigured production installs.
* When ownership is confirmed the helper:
  * Adds the LaurelID package to the lock-task allowlist.
  * Enables lock-task features so key system surfaces (keyguard, notifications,
    system info, and global actions) continue to work while the task is pinned.

## Lock Task + Immersive UI

* `ScannerActivity` and every kiosk surface (`LoginActivity`, `ResultActivity`,
  `AdminActivity`) call the `KioskUtil` helpers to keep the screen on, dismiss
  the keyguard, and apply immersive flags each time they resume or regain focus.
* `ScannerActivity` re-enters lock task mode on every resume and whenever the
  window regains focus.

## Watchdog Relaunch

* `KioskWatchdogService` runs as a foreground service. Activities mark
  themselves visible via `notifyScannerVisible(true/false)` in `onResume` and
  `onPause`.
* If the watchdog observes that no kiosk activity has been visible for an
  entire interval it relaunches `ScannerActivity`, ensuring recovery after a
  crash, user-initiated HOME press, or an unexpected overlay.
* The service starts on boot and whenever the app process launches so it can
  resurrect the kiosk experience automatically.

## Provisioning Script

* `scripts/provision_device_owner.sh` automates the ADB sequence required to
  provision a dedicated device:
  * `dpm set-device-owner com.laurelid/.kiosk.LaurelIdDeviceAdminReceiver`
  * `dpm set-lock-task-packages` / `set-lock-task-features`
  * Launcher binding, dialog allowlists, standby/idle relaxations, and a final
    reboot.

Refer to [DeviceOwnerSetup.md](../DeviceOwnerSetup.md) for step-by-step usage
instructions.
