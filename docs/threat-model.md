# Threat Model

This threat model centers on the LaurelID verifier kiosk that consumes ISO/IEC 18013-5 mobile driving credentials (mDLs) and AAMVA-compliant trust lists. It maps the critical assets, trust boundaries, and mitigations that protect the verifier from tampering, credential forgery, and privacy leakage.

## Critical Assets
- **Holder data**: ISO 18013-5-compliant mDL payloads, selective disclosure responses, and derived attributes cached for verification.
- **Trust anchors**: AAMVA trust lists, certificate pin sets, and app signing keys used to authenticate issuers and the verifier application.
- **Verifier policy configuration**: Feature flags, kiosk mode enforcement settings, and allowed endpoint definitions that constrain admin overrides.
- **Audit evidence**: Verification logs, device-owner provisioning attestations, and CI results that demonstrate compliance.

## Trust Boundaries
- **mDL presentation boundary**: Data crossing from the holder-controlled device into the kiosk via BLE/QR. Integrity depends on ISO 18013-5 session negotiation and selective disclosure agreements.
- **Network boundary**: Outbound TLS 1.2+ traffic from the kiosk to the trust-list endpoint. Release builds refuse arbitrary endpoints; staging builds isolate overrides.
- **Admin boundary**: Device-owner mode restricts configuration to authenticated admins and disables side-loading or launcher escapes.
- **Data storage boundary**: Local caches reside in Android scoped storage; logs rotate to prevent retention beyond the minimum required for audits.

## Attack Surfaces & Mitigations
- **Malicious issuers or MITM**: Mitigated through TLS 1.2+, certificate pinning for trust-list retrieval, and validation against AAMVA trust anchors.
- **Compromised kiosk hardware**: Device-owner provisioning, lock-task mode, and Play Integrity gating (future verdict enforcement) prevent privilege escalation.
- **Spoofed mDL payloads**: ISO 18013-5 cryptographic verification, issuer signature checks, and revocation via trust list updates.
- **Untrusted admin overrides**: Release builds reject runtime endpoint changes; staging builds require explicit flagging and telemetry.
- **Data exfiltration**: Scoped storage, network security config denying cleartext, and strict permission model minimize unauthorized export.
- **Denial of service**: Watchdogs for trust-list freshness and CI coverage to catch regressions; kiosk auto-restart scripts maintain availability.

## Residual Risks & Follow-Up Actions
- **Play Integrity verdict stub**: Pending backend integration; track as P1 before production launch.
- **Manual trust-list updates**: Ensure operational runbooks include rotation cadence and out-of-band verification of new anchors.

## Alignment with ISO 18013-5 & AAMVA Guidance
- Follows ISO 18013-5 recommendations for verifier authentication, secure session establishment, and data minimization.
- Respects AAMVA trust-list governance by pinning to authoritative issuer certificates and documenting override procedures.
- Implements principle of least privilege for verifier features, matching AAMVA kiosk deployment best practices.
