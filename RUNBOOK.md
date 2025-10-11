# LaurelID Verify with Wallet Runbook

## Feature Flags
- `BuildConfig.USE_OFFLINE_TEST_VECTORS` – enable deterministic HPKE/COSE replay using the bundled sample vectors.
- `BuildConfig.DEVPROFILE_MODE` – bypass issuer PKI validation and allow Apple developer-profile device chains while still enforcing device signature checks.
- `BuildConfig.TRANSPORT_QR_ENABLED` / `TRANSPORT_NFC_ENABLED` – gate available engagement transports at runtime.

Toggle flags via Gradle `buildConfigField` overrides or by creating dedicated product flavors if you need persistent variants.

## HPKE Key Lifecycle
1. The kiosk generates its X25519 HPKE key the first time `AndroidHpkeKeyProvider` runs. Keys live in the Android Keystore under alias `laurelid_hpke_x25519` with StrongBox preference.
2. To rotate keys manually:
   ```shell
   adb shell cmd keystore wipe laurelid_hpke_x25519
   ```
   Relaunching the verifier regenerates a fresh keypair.
3. Export the public key for QR/NFC bootstrapping:
   ```kotlin
   val publicKeyB64 = Base64.encodeToString(hpkeKeyProvider.getPublicKeyBytes(), Base64.NO_WRAP)
   ```
   Surface this value to any out-of-band provisioning workflow (e.g., operator portal).

## Updating IACA Trust Roots
- Bundled roots live as `.cer` files under `app/src/main/assets/trust/iaca/` (see the README in that directory for expected filenames).
- Replace the DER certificates when DHS/IACA publishes updates, then re-run `verifyProdAnchors` (or any `assemble*Release` target) to confirm the new anchors parse successfully.
- For staged rollouts, keep staging and production directories in sync so that configuration cache hits remain valid across environments.

## Offline Test Vectors
- JSON sample lives at `app/src/main/res/raw/sample_hpke_vector.json`.
- Enable `USE_OFFLINE_TEST_VECTORS` to run the verifier end-to-end without external devices. The sample decrypts to the mock plaintext documented inside the JSON.

## Operator Workflow on Elo Kiosk
1. Launch `VerifyActivity` (kiosk home activity) – the screen displays a fullscreen QR code with NFC fallback instructions.
2. Patron scans the QR with Apple Wallet or taps via NFC. The engagement transcript is captured in-memory.
3. Once the mobile device approves, the kiosk transitions to the pending spinner and final result screen showing age badge, name, and portrait.
4. Results auto-clear after 15 seconds when `volatile mode` (default) is enabled; no data persists to disk.
5. If the patron lacks an mDL, use the PDF417 fallback decoder (`Pdf417FallbackDecoder`) with the built-in camera or external scanner input.

## Privacy Checklist
- Private HPKE keys live exclusively in the hardware-backed keystore.
- Minimal claims + portrait are stored in-memory only for the active session and purged on reset.
- No verification artifacts are written to disk unless developers disable volatile mode intentionally.
- Operator UI surfaces only age badge, name, and portrait; audit strings are operator-facing only.

## NFC and QR Tips
- `NfcEngagementTransport.bind(activity)` must be invoked from the foreground activity before calling `start()`.
- For hardware scanners presenting as USB HID, ensure the system IME is suppressed in kiosk mode so barcode input reaches the fallback decoder cleanly.

## Updating the Fallback Barcode Parser
- `Pdf417FallbackDecoder` uses ML Kit. Update the ML Kit dependency in `libs.versions.toml` and keep the parsing heuristics in `parsePayload` aligned with jurisdiction-specific DL formats.
