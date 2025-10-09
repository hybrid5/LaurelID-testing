# Developing LaurelID Kiosk

This document describes how to exercise the Verify with Wallet flow offline using the bundled test vectors.

## Prerequisites
- Android Studio Ladybug or newer with the AGP version defined in `gradle/catalogs/libs.versions.toml` installed.
- An emulator or Elo kiosk running Android 13+.
- The repository cloned locally with `git lfs` configured for large assets.

## Offline Demo Flow
1. Build the `stagingDebug` variant. The build embeds HPKE keys and COSE payloads under `app/src/test/resources/`.
2. Launch the app and open the kiosk screen. The QR code encodes the presentation request, HPKE recipient key, and session nonce per ISO/IEC 18013-7 §7.
3. From the command line, replay the encrypted payload:
   ```bash
   adb push app/src/test/resources/mdoc/payloads/sample_ciphertext.b64 /sdcard/
   adb shell 'cat /sdcard/sample_ciphertext.b64 | base64 -d > /sdcard/sample_ciphertext.bin'
   adb shell am broadcast -a com.laurelid.TEST_CIPHERTEXT --es path /sdcard/sample_ciphertext.bin
   ```
   Implementers can wire this broadcast to `KioskViewModel.onCiphertextReceived` for rapid iteration.
4. The kiosk decrypts the payload using `BouncyCastleHpkeEngine` (RFC 9180 §5) and verifies the COSE structure with the bundled IACA roots.

## Running Tests
```bash
./gradlew test
```
The unit tests cover HPKE envelope parsing, COSE attribute filtering, trust chain validation, and the kiosk view model state machine.

## Coding Guidelines
- Follow Kotlin style with coroutines and explicit suspend functions for I/O.
- Keep logging free of PII; the structured logger enforces redaction.
- Annotate new KDoc with references to ISO/IEC 18013, RFC 9180, or Apple Verify with Wallet guidance where relevant.
