# Accessibility Test Checklist

The following checklist captures the manual and automated steps we run to confirm kiosk UI accessibility.

## Visual and Interaction
- [ ] Confirm all interactive controls (buttons, switches, input fields) meet the 48dp minimum touch target using Layout Inspector or on-device Developer Settings.
- [ ] Validate text and control contrast against their backgrounds meets WCAG 2.1 AA ratios (4.5:1 for body text, 3:1 for large text) with the Accessibility Scanner contrast check.
- [ ] Exercise the UI at 200% font scale (Settings → Display → Font size) and ensure text wraps without clipping or overlapping other components.
- [ ] Inspect focus order with a hardware keyboard or Android's switch access to verify it follows the visual order and groups logically.

## Screen Reader Coverage
- [ ] Enable TalkBack and navigate each kiosk screen, confirming content descriptions and status updates are announced clearly without duplicates.
- [ ] Trigger scanning and verification flows to ensure live regions announce state changes (e.g., "Scanning", "Verifying", result titles) and progress indicators expose meaning.
- [ ] Verify decorative elements such as the verification icon are skipped by TalkBack, while actionable controls (debug log button, Save) include meaningful labels.

## Error and Status Feedback
- [ ] Simulate error conditions (camera permission denial, invalid PIN, failed verification) and check that error messages are announced and visually distinct.
- [ ] Confirm input validation errors provide both textual feedback and clear color contrast that remains perceivable for color-blind users.
- [ ] Ensure toast or snackbar notifications have accessible wording and are complemented by logged telemetry for auditability when applicable.

## Assistive Technology Compatibility
- [ ] Test with Android Accessibility Scanner to capture any remaining warnings, documenting and triaging findings.
- [ ] Run baseline instrumentation tests (where available) with Espresso accessibility checks enabled to guard regressions.
- [ ] Review telemetry for accessibility-related events to ensure no personally identifiable information (PII) is captured.
