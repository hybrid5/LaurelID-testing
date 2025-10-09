# LaurelID Kiosk Security Considerations

## Threat Model
- **Kiosk Theft:** Physical removal of the device could expose stored HPKE keys. The `AndroidHpkeKeyProvider` stores keys inside Android Keystore with StrongBox fallback, preventing export even with root access (Android Keystore documentation). Enable device encryption and lock task mode on Elo kiosks.
- **Key Extraction via Debug Builds:** Debug builds allow HPKE key injection for testing. Production builds must disable `installDebugKey` by stripping debug code paths.
- **Credential Replay:** Wallet responses include session nonces and transcripts hashed into the COSE external AAD (ISO/IEC 18013-5 §9.4). Reject any payload that reuses a prior nonce or session identifier.
- **BLE/NFC MITM:** The transport abstraction enforces timeouts and expects HPKE ciphertexts bound to session transcripts. Future BLE support must validate connection security (LE Secure Connections, rotating resolvable addresses).
- **Apple Device Attestation Variance:** Developer test profiles ship with relaxed device signatures. The verifier exposes feature flags to allow test roots while production requires full chain validation.

## Hardening Checklist
1. Provision kiosks in device-owner mode with lock task and disallow USB debugging.
2. Rotate HPKE keys quarterly and audit `HpkeKeyMetadata` for staleness.
3. Bundle the IACA root set and schedule signed trust list updates; reject payloads when trust anchors expire.
4. Use structured logging without PII; include deterministic event codes for audit.
5. Ensure offline retention policies purge verification results after the configured window.
