# Privacy Posture

The LaurelID verifier kiosk processes ISO/IEC 18013-5 mobile driving credential (mDL) data inside controlled venues. This document describes the data lifecycle, consent controls, and retention posture aligned with AAMVA privacy principles for verifiers.

## Data Collected
- **Selective disclosure attributes** from the holder's mDL session (e.g., name, date of birth, driving privilege codes) negotiated per ISO 18013-5 request templates.
- **Verification telemetry** such as timestamp, issuing authority identifier, and trust-list version to support auditability.
- **Device health evidence** including Play Integrity verdict state, kiosk provisioning status, and app version.

## Lawful Basis & Consent
- The verifier requests only attributes required for the relying party's purpose and surfaces them to the holder prior to acceptance, consistent with ISO 18013-5 consent prompts.
- Relying parties must display signage that explains the verification purpose and references the venue's privacy notice.
- Administrative overrides and manual entry modes remain disabled in production, preventing collection beyond the standardized mDL payload.

## Data Handling & Retention
- **On-device storage**: Selective disclosure responses are retained in volatile memory and cleared when the verification session ends or after 15 minutes of inactivity.
- **Logs**: Success/failure telemetry is stored in rotating files capped at 24 hours, redacting personally identifiable information per AAMVA guidelines.
- **Exports**: No automated data export occurs. Manual log retrieval requires authenticated admin access and is tracked in the provisioning checklist.
- **Deletion**: Device-owner reset scripts remove cached trust lists, logs, and application data, restoring factory settings before reassignment.

## Access Controls
- Device-owner mode locks the kiosk to the LaurelID launcher, preventing background apps from reading session data.
- Scoped storage and denied cleartext network policies block third-party apps or network observers from capturing sensitive fields.
- Admin console features are gated by Play Integrity verdict (stubbed today, enforced prior to production) to detect compromised builds.

## Data Minimization & Transparency
- Attribute requests mirror AAMVA trust framework minimums. Optional attributes remain unrequested unless mandated by the relying party's compliance profile.
- UI copy in the verifier highlights which attributes were shared and for how long they remain visible.
- CI checklists include privacy regression reviews to ensure new features do not expand data collection without documented justification.

## Third-Party Sharing
- Trust-list updates are retrieved from AAMVA-distributed endpoints over pinned TLS. No holder data is transmitted to AAMVA or issuers beyond protocol-level acknowledgements.
- Support engagements rely on scrubbed telemetry. Any PII required for troubleshooting must be gathered directly from the holder with fresh consent.

## Alignment with ISO 18013-5 & AAMVA Principles
- Honors ISO 18013-5 requirements for user consent, selective disclosure, and verifier confidentiality.
- Implements AAMVA privacy principles by limiting data to the minimum necessary, defining retention limits, and documenting holder notice obligations.
- Maintains accountability by coupling provisioning runbooks, audit evidence capture, and privacy reviews within release gating criteria.
