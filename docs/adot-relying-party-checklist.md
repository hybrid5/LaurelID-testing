# ADOT / AAMVA Relying Party Submission Checklist (Draft)

This draft checklist guides LaurelID teams through preparing the compliance package required to onboard as a relying party for the ADOT MVD Digital ID Program under the AAMVA trust framework.

## 1. Program Preparation
- [ ] Assign executive sponsor and compliance lead; document responsibilities.
- [ ] Review latest ADOT MVD Digital ID Program guide and relying party terms of use.
- [ ] Confirm scope of verification use cases (age, identity, privilege) and applicable AAMVA minimum data elements.

## 2. Policy & Documentation
- [ ] Finalize Privacy & PII Handling Policy (docs/privacy.md) and obtain internal approval.
- [ ] Finalize Security Whitepaper (docs/security.md) and map controls to AAMVA trust framework categories.
- [ ] Update threat model (docs/threat-model.md) and attach to submission packet.
- [ ] Compile incident response plan summary and notification procedures.

## 3. Technical Evidence
- [ ] Generate latest attestation logs (Play Integrity, key attestation) for production hardware.
- [ ] Export trust-list validation results and issuer certificate chain reports.
- [ ] Provide architecture diagrams showing network segmentation and kiosk deployment topology.
- [ ] Package SAST/DAST reports, penetration test summaries, and SBOM vulnerability status.

## 4. Operational Controls
- [ ] Document kiosk provisioning checklist, including MDM baselines and tamper seal procedures.
- [ ] Capture log retention and redaction configurations with screenshots or configuration exports.
- [ ] Provide training materials for relying party attendants covering consent messaging and privacy obligations.
- [ ] Record results of most recent business continuity and incident response tabletop exercises.

## 5. ADOT Coordination Steps
- [ ] Prepare cover letter summarizing relying party use case, contact information, and compliance posture.
- [ ] Email submission packet to ADOT MVD Digital ID Program (digitalid@azdot.gov) and request onboarding review.
- [ ] Call ADOT MVD program management office (602-712-2700) to confirm receipt and schedule technical validation.
- [ ] Capture ADOT ticket/case number and distribute internally for tracking.

## 6. AAMVA Trust Framework Requirements Gathering
- [ ] Retrieve latest AAMVA trust framework documentation from AAMVA member portal; verify coverage of privacy, security, governance, and interoperability sections.
- [ ] Map LaurelID controls to each required principle, noting evidence location and owner.
- [ ] Identify any gaps or compensating controls needed for ADOT submission and assign remediation tasks.

## 7. Submission Readiness Review
- [ ] Conduct internal mock audit with compliance, security, and engineering stakeholders.
- [ ] Obtain sign-off from executive sponsor and legal counsel before sending final package.
- [ ] Archive final submission, ADOT correspondence, and approval artifacts in compliance evidence vault.

> **Note:** This checklist is a living document. Update it as ADOT publishes new guidance or AAMVA revises trust framework requirements.
