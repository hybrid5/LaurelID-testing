# Privacy & PII Handling Policy

The LaurelID verifier kiosk processes ISO/IEC 18013-5 mobile driving credential (mDL) data on behalf of relying parties seeking acceptance into the Arizona Department of Transportation (ADOT) Motor Vehicle Division (MVD) Digital ID ecosystem. This policy consolidates privacy controls, personally identifiable information (PII) handling procedures, and alignment with American Association of Motor Vehicle Administrators (AAMVA) trust framework principles.

## Governance & Scope
- **Program oversight**: The LaurelID compliance lead is accountable for documenting verifier privacy controls, evidence collection, and coordination with ADOT MVD program management.
- **Regulatory drivers**: ISO/IEC 18013-5, AAMVA mDL Implementation Guidelines, Arizona state privacy statutes, and the ADOT relying party terms of use govern our processing activities.
- **Data subjects**: Arizona credential holders presenting an mDL at a LaurelID-enabled relying party location.

## Data Inventory
- **Selective disclosure attributes** from the holder's mDL session (e.g., name, date of birth, driving privilege codes) negotiated per ISO 18013-5 request templates. Attribute requests default to the AAMVA minimum data elements for age/identity verification unless a signed ADOT data scope waiver is on file.
- **Verification telemetry** (timestamp, issuing authority identifier, trust-list version, kiosk ID, relying party ID) retained for accountability and troubleshooting.
- **Device health evidence** including Play Integrity verdict state, kiosk provisioning status, firmware version, and local configuration hash.

## PII Handling Procedures
- **Collection**: mDL attributes are obtained only after explicit holder approval within the ISO 18013-5 consent flow. Offline/manual entry paths remain disabled.
- **Use**: PII is displayed solely to authorized attendants for the duration of the verification session. Export, copy, or screen-capture capabilities are disabled in the hardened kiosk profile.
- **Storage**: Selective disclosure responses are held in volatile memory buffers that are cleared immediately at session termination or after 15 minutes of inactivity. Persistent storage of raw PII is prohibited.
- **Transmission**: Holder attributes are never transmitted off-device. Telemetry sent to LaurelID services excludes PII, substituting hashed session IDs and relying party pseudonyms.
- **Retention**: Rotating device logs retain only redacted telemetry for up to 24 hours. Audit exports replace holder identifiers with salted hashes before secure upload to the compliance evidence vault.
- **Deletion**: Device-owner reset scripts remove trust lists, configuration secrets, and logs during deprovisioning or when Play Integrity attestation fails.

## Lawful Basis & Consent
- The verifier requests only attributes required for the relying party's documented purpose and surfaces them prior to holder acceptance, consistent with ISO 18013-5 consent prompts.
- Relying parties must display signage that explains the verification purpose, duration of display, appeals process, and references the venue's privacy notice.
- Administrative overrides remain disabled in production, preventing collection beyond the standardized mDL payload.

## Data Minimization & Transparency
- Attribute requests mirror AAMVA trust framework minimums. Optional attributes remain unrequested unless mandated by the relying party's compliance profile and documented in the ADOT submission packet.
- The kiosk UI highlights which attributes were shared, when they will disappear from view, and how holders can revoke future sharing within the issuer wallet.
- Release readiness reviews include privacy regression gates to ensure new features do not expand data collection without documented justification and ADOT approval.

## Access Controls
- Device-owner mode locks the kiosk to the LaurelID launcher, preventing background apps from reading session data.
- Scoped storage and denied cleartext network policies block third-party apps or network observers from capturing sensitive fields.
- Admin console features are gated by Play Integrity verdict (stubbed today, enforced prior to production) and hardware-backed key attestation to detect compromised builds.

## Third-Party Sharing
- Trust-list updates are retrieved from AAMVA-distributed endpoints over pinned TLS. No holder data is transmitted to AAMVA or issuers beyond protocol-level acknowledgements required for certificate status checks.
- Support engagements rely on scrubbed telemetry. Any PII required for troubleshooting must be gathered directly from the holder with fresh consent and recorded in the ADOT incident report template.

## AAMVA Trust Framework Alignment
- **Consent & transparency**: Implements ISO 18013-5 selective disclosure, relies on issuer-provided consent dialogs, and requires on-premise relying party notices.
- **Data minimization**: Requests only AAMVA minimum data elements for the declared use case; deviations require ADOT MVD waiver approval.
- **Security & accountability**: Couples provisioning runbooks, audit evidence capture, and quarterly privacy impact assessments with release gating criteria. Evidence is prepared to satisfy AAMVA Privacy Principle 7 (Accountability).

## Incident Response & Holder Rights
- Suspected privacy incidents trigger the LaurelID Security Incident Response Plan with ADOT notification within 24 hours of confirmation.
- Holders may request an audit of their verification history via the LaurelID privacy inbox (privacy@laurelid.example). Responses are provided within 30 days, referencing redacted telemetry to maintain kiosk security.
- Deletion or restriction requests are fulfilled by purging associated telemetry hashes and providing confirmation to the holder and ADOT MVD program contact.
