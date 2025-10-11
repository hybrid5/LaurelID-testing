# LaurelID v1.0 Production Readiness Report

**Verdict:** ✅ Production Ready
**Score:** 9.5 / 10
**Reason:** Release builds now fail closed without production IACA anchors while CI and local workflows assemble both staging and production artifacts on pinned toolchains with full QR transcript coverage.

## Findings

### Versions
- Version catalog pins the mandated stack (AGP 8.13, Kotlin 2.0.21, Hilt 2.52) and all dependencies resolve through aliases, eliminating inline overrides or drifting `+` coordinates.【F:gradle/catalogs/libs.versions.toml†L1-L53】【F:app/build.gradle.kts†L205-L220】
- Android module build scripts delegate plugin versions to the catalog and avoid ad-hoc repository overrides, keeping dependency resolution deterministic.【F:settings.gradle.kts†L1-L28】【F:app/build.gradle.kts†L8-L23】

### Build / Reproducibility
- Gradle toolchains rely on pre-installed JDK 17 with auto-download disabled while leaving SDK downloads off, keeping configuration cache, caching, and parallelism active for reproducible builds.【F:gradle.properties†L5-L24】
- Release variants depend on the `verifyProdAnchors` task, which refuses to assemble when valid `.cer` anchors are absent or malformed, and project docs call out the required filenames and fingerprints.【F:app/build.gradle.kts†L171-L195】【F:app/src/main/assets/trust/iaca/README.txt†L1-L15】
- CI and local workflows exercise staging and production release builds under configuration cache using pre-generated dummy anchors, proving the graph succeeds without Foojay downloads.【F:.github/workflows/android.yml†L1-L62】【scripts/ci/install_dummy_iaca_anchor.sh†L1-L18】

### Security
- `AssetTrustProvider` now reads anchors exclusively from `assets/trust/iaca`, throwing if the directory or certificates are missing, ensuring the verifier fails closed without production roots.【F:app/src/main/java/com/laurelid/trust/TrustBootstrap.kt†L75-L133】
- Trust seed and BuildConfig values no longer embed the LaurelID test root, keeping production manifests empty until real anchors load at runtime.【F:app/src/main/assets/trust_seed.json†L1-L8】【F:app/build.gradle.kts†L49-L71】
- HPKE keys remain keystore-backed with debug import locked behind `BuildConfig.DEBUG`, preventing release exports of private material.【F:app/src/main/java/com/laurelid/auth/crypto/HpkeEngine.kt†L76-L129】

### Coverage
- New `SessionManagerE2eTest` drives a QR/Web engagement through transcript creation, validates a signed payload, rejects replay attempts, and asserts failure when anchors are absent, covering the P0 flow requirements.【F:app/src/androidTest/java/com/laurelid/auth/session/SessionManagerE2eTest.kt†L47-L170】
- Existing crypto vector tests continue to exercise HPKE/COSE behavior, complementing the new end-to-end scenario.【F:app/src/androidTest/java/com/laurelid/auth/crypto/HpkeAndCoseVectorTest.kt†L160-L286】

### CI
- CI workflow provisions Temurin 21 and 17 locally, installs Android SDK 36 with matching Build-Tools 36.0.0 ahead of time, seeds dummy production anchors, and runs lint/assemble/test for both staging and production variants under configuration cache with cached artifacts.【F:.github/workflows/android.yml†L1-L62】【scripts/ci/install_dummy_iaca_anchor.sh†L1-L18】
- Gradle properties disable on-demand SDK downloads, ensuring CI jobs stay hermetic once the workflow installs the required packages.【F:gradle.properties†L21-L24】

## Scorecard
```json
{
  "build": 9.5,
  "security": 9.5,
  "correctness": 9.5,
  "test_coverage": 9.5,
  "maintainability": 9.5,
  "overall": 9.5,
  "top_risks": [
    "Operational: production DER certificates must be staged in assets/trust/iaca with verified hashes before releasing builds."
  ],
  "must_fix_P0": [],
  "should_fix_P1": [],
  "nice_to_have_P2": [
    "Add device-connected NFC instrumentation once hardware labs are available to mirror field conditions."
  ]
}
```
