# Offline Demo Walkthrough

1. Launch the kiosk fragment inside your host activity (`KioskFragment`).
2. Use `adb shell am broadcast` or the test harness to deliver the sample ciphertext from `app/src/test/resources/mdoc/payloads/sample_ciphertext.b64`.
3. Observe the kiosk UI transition: `Idle → Engaging → Waiting Approval`. Scan the QR code with a compliant wallet or replay the ciphertext via `KioskViewModel.onCiphertextReceived`.
4. The `SessionManager` decrypts the payload, verifies issuer/device signatures against local IACA roots, and the UI shows `Age 21+ ✓` in the result banner.
5. Reset the kiosk with the on-screen `Reset` button to purge transcripts.

