# LaurelID Ship Audit

**Verdict:** ✅ Production Ready
**Score:** 9.5 / 10

## Category Summary

| Category | Status | Notes |
| --- | --- | --- |
| P0 – Trust & Verification | **PASS** | Production flavor only exposes empty `TRUST_LIST_MANIFEST_ROOT_CERT`, anchors load exclusively from assets and missing roots throw `TrustAnchorsUnavailable`. |
| P1 – Build / CI / Toolchains | **PASS** | Gradle pinned to local JDK 17 without Foojay, CI provisions Temurin 21/17 plus SDK 36 & Build-Tools 36.0.0 and assembles staging+production with configuration cache. |
| P2 – Docs / Consistency | **PASS** | RUNBOOK and readiness report aligned with `.cer` workflow, lint baseline regenerated, workflow/docs updated to reflect production release builds. |

## Highlights

- `verifyProdAnchors` blocks release if `.cer` anchors are missing or malformed, and `ResourceTrustStore` now throws when anchors cannot be loaded, keeping verifier trust fail-closed.
- CI seeds ephemeral `.cer` anchors via `scripts/ci/install_dummy_iaca_anchor.sh` before running lint, staging debug, production release, and unit tests on preinstalled toolchains.
- Lint baseline refreshed after removing PEM references and duplicate manifest fields, keeping the scan output clean while retaining known SDK-targeted suppressions.

## Residual Risks

1. Operational: real IACA anchors must be staged under `app/src/main/assets/trust/iaca/` with verified hashes prior to tagging a release.
2. Instrumentation: NFC hardware E2E remains manual; consider adding robolectric/device coverage when hardware labs are available.
