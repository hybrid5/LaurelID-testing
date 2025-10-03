# Security Whitepaper

This whitepaper summarizes the LaurelID verifier kiosk security architecture, operational safeguards, and evidence required for the Arizona Department of Transportation (ADOT) Motor Vehicle Division (MVD) Digital ID relying party onboarding. It aligns with American Association of Motor Vehicle Administrators (AAMVA) trust framework expectations for verifiers handling ISO/IEC 18013-5 mobile driving credential (mDL) presentations.

## 1. System Architecture Overview
- **Kiosk hardware**: Hardened Android tablet locked to device-owner mode with disabled USB debugging, biometric login, and peripheral whitelisting. Hardware root of trust anchors verified boot and key attestation.
- **Verifier application**: LaurelID verifier app is implemented with AppCompat activities and XML layouts, integrates the ISO 18013-5 reader stack, and enforces Google Play Integrity verdicts before exposing privileged administration surfaces.
- **Network zones**: Segmented VLAN with outbound access limited to AAMVA trust-list distribution, ADOT status services, and LaurelID compliance endpoints over pinned TLS 1.2+.
- **Trust list client**: Outbound calls are made with an OkHttp stack configured for TLS 1.3/1.2, connection retries, and rotating certificate pins enforced per endpoint policy.
- **Management plane**: Mobile device management (MDM) platform enforcing configuration baselines, remote wipe, and security policy compliance reporting.

## 2. Threat Model & Controls
- **Spoofed credential presenters** → Mitigated through ISO 18013-5 cryptographic verification, revocation list validation, and issuer trust anchors sourced from AAMVA federated lists.
- **Compromised kiosk** → Mitigated through Play Integrity verdict enforcement, kiosk watchdog restarts that keep the scanner in the foreground, and kiosk-mode UI hardening that suppresses navigation and back actions.
- **Network eavesdropping** → Mitigated through certificate pinning, TLS 1.3/1.2 cipher suites, and Wi-Fi Protected Access 3 (WPA3) enterprise authentication.
- **Unauthorized admin actions** → Mitigated with role-based access in MDM, privileged task logging, and dual approval for configuration pushes.
- **PII exfiltration** → Prevented via scoped storage, Data Loss Prevention (DLP) policies, disabled clipboard/screenshots, and telemetry events constrained to event metadata without subject identifiers while verification fails closed if required artifacts are missing.
- **Forged or altered issuer responses** → Mitigated through fail-closed verification that requires trusted issuer certificates, revocation checks, digest validation, and signature verification before completing a transaction.

## 3. Secure Development Lifecycle
- Security requirements derived from ISO 18013-5, AAMVA mDL Implementation Guidelines, and OWASP MASVS.
- Threat modeling (docs/threat-model.md) is refreshed quarterly or upon major feature additions.
- Static and dynamic application security testing (SAST/DAST) gates the main branch; results are archived for ADOT submission evidence.
- Third-party dependencies undergo Software Bill of Materials (SBOM) review and vulnerability scanning with remediation SLA of 30 days for medium and 7 days for high severity issues.

## 4. Operational Security
- **Access management**: SSO-enforced least-privilege access to telemetry dashboards, compliance evidence vault, and build pipelines. Privileged actions require hardware security key MFA.
- **Logging & monitoring**: Centralized logging (PII redacted) with anomaly detection for repeated verification failures, attestation drops, or network deviations.
- **Telemetry handling**: Production builds register a no-op exporter so verification events are dropped on-device, while staging builds write JSON telemetry (event name, timestamps, outcome flags) to private app storage only during debug sessions.
- **Incident response**: Security incidents follow the LaurelID IR playbook with containment, eradication, and ADOT notification within 24 hours. Evidence artifacts include affected kiosk IDs, timeline, and corrective actions.
- **Business continuity**: Daily encrypted backups of configuration baselines and trust lists stored in geographically redundant vaults. Recovery procedures rehearse annual tabletop exercises with ADOT observers invited.

## 5. Compliance Alignment
- **AAMVA Trust Framework**: Demonstrates controls for privacy, security, governance, and interoperability. Evidence packages include policy documents, penetration test summaries, and attestation logs.
- **ADOT Relying Party Requirements**: Maintains signed Memorandum of Understanding (MOU), adheres to relying party terms, and provides quarterly compliance attestations to the ADOT MVD Digital ID Program.
- **NIST References**: Controls mapped to NIST SP 800-63-3 (IAL2/AAL2) and NIST CSF Identify/Protect/Detect/Respond/Recover functions to contextualize risk coverage.

## 6. Roadmap & Continuous Improvement
- Expand hardware-backed key management with support for FIPS 140-3 validated secure elements as ADOT updates hardware guidance.
- Integrate automated compliance reporting that exports control evidence into ADOT's relying party portal.
- Schedule independent penetration testing 60 days prior to production go-live and annually thereafter, sharing executive summaries with ADOT and relevant relying parties.

## 7. Contact & Next Steps
- **Security point of contact**: security@laurelid.example
- **Incident hotline**: +1-480-555-0119 (24/7 on-call)
- **ADOT coordination**: Submit security evidence and inquiries via the ADOT MVD Digital ID Program contact (digitalid@azdot.gov) and follow up with the program management office at 602-712-2700 for scheduling onboarding reviews.
