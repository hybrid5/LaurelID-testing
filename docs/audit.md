# Audit Summary

This audit reviewed the kiosk Android app and the FastAPI ingestion backend for test-only
configuration and documentation drift.

## Issues Addressed

- **Admin access gesture too short:** `ScannerActivity` reduced the admin unlock hold to three
  seconds for easier testing. Restored the documented five-second requirement to prevent
  accidental activation on production kiosks.
- **Stale backend authentication guidance:** Backend documentation still referenced legacy
  `HS256` shared-secret tokens while the implementation enforces RS256 with JWKS discovery.
  Updated the README and example client to require explicit JWKS configuration and real
  credentials instead of placeholder test values.
- **Duplicate trust list guidance:** The project README repeated the trust list configuration
  instructions in two sections, which could cause confusion. Deduplicated the text and clarified
  the Play Integrity behavior to match the current implementation.

## Outstanding Follow-Ups

- **Cryptographic completeness:** `ISO18013Parser` and `WalletVerifier` still have TODO markers
  for COSE / SD-JWT validation and full trust chain enforcement. These remain the largest
  functional gaps before a production launch.
- **Hardware integrations:** `TransactionManager` continues to stub printer/POS connectivity.
  Production deployments will need real device integrations and associated end-to-end testing.
- **Play Integrity project number:** Ensure the `PLAY_INTEGRITY_PROJECT_NUMBER` build config field
  is set per environment. Without it, the admin console remains locked because the verdict is
  treated as unknown.

No unused code paths or obsolete scripts were identified beyond the items above.
