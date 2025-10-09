# Feature Flags

| Flag | Location | Default | Description |
| ---- | -------- | ------- | ----------- |
| `TRANSPORT_QR_ENABLED` | `BuildConfig` | `true` | Enables QR engagement (ISO/IEC 18013-7 §8.2). Disable to force NFC/BLE. |
| `TRANSPORT_NFC_ENABLED` | `BuildConfig` | `true` | Enables NFC reader mode handoffs. Requires device support. |
| `TRANSPORT_BLE_ENABLED` | `BuildConfig` | `false` | Placeholder for BLE engagement. When enabled, implement `BleTransport`. |
| `DEVPROFILE_MODE` | `BuildConfig` | `false` | Allows Apple Developer Integrator device certificates without full chain validation. |
| `USE_OFFLINE_TEST_VECTORS` | `BuildConfig` | `false` | When true, kiosk loads bundled ciphertexts for demos instead of hardware capture. |
| `ALLOW_TEST_ROOTS` | Future | `false` | Planned toggle to accept bundled non-production IACA roots. |
| `RETENTION_DAYS` | Config | `30` | Number of days to retain audit logs before purge. |
