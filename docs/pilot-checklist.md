# Pilot Go/No-Go Checklist

Use this checklist before promoting a build into the pilot program. Each criterion requires explicit validation steps and evidence capture so that reviewers can quickly confirm readiness.

## 1. Cryptographic Verification
- **Criteria**: All instrumented cryptographic unit and integration tests must pass on the targeted commit.
- **Validation Steps**:
  1. Run `./gradlew testCrypto` (or the appropriate aggregated task for crypto coverage).
  2. Capture the console output showing a `BUILD SUCCESSFUL` result.
- **Evidence Link**: Attach the uploaded CI job or local log artifact URL here.

## 2. Kiosk Lock-Task Mode
- **Criteria**: Device-owner provisioning must keep the LaurelID launcher pinned in lock-task mode without escape paths.
- **Validation Steps**:
  1. Provision the reference device using `scripts/provision_device_owner.sh`.
  2. Reboot and confirm that the app auto-starts and remains in lock-task mode (Home and Overview buttons disabled).
  3. Document the manual steps taken to exit lock-task (if applicable) to ensure recovery paths remain functional.
- **Evidence Link**: Provide photos or a short video hosted in the shared drive demonstrating lock-task enforcement.

## 3. Continuous Integration Health
- **Criteria**: All required CI workflows (build, unit tests, lint, instrumentation smoke) are green for the release commit.
- **Validation Steps**:
  1. Trigger the full CI pipeline or confirm it ran automatically for the candidate commit.
  2. Verify success status in the CI dashboard for every mandatory job.
- **Evidence Link**: Paste links to the CI run summary and key job detail pages.

## 4. PII Handling Audit
- **Criteria**: The latest PII audit for the build shows no open findings and confirms logging scrubs sensitive data.
- **Validation Steps**:
  1. Review the current PII data inventory spreadsheet and confirm no changes introduce new data flows.
  2. Validate that redaction utilities cover all new logging calls since the last audit.
  3. Obtain sign-off from the privacy lead (email or ticket comment).
- **Evidence Link**: Link to the signed-off audit report or ticket reference in the compliance tracker.

---

When all sections show ✅ with working evidence links, record the reviewer, date, and pilot tag in the release log. Block pilot launch if any criterion is unmet or evidence is incomplete.
