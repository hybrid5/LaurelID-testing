LaurelID Production Trust Anchors
=================================

Place the production IACA root certificates in this directory before
building a release artifact. Each certificate must be provided as a
DER-encoded `.cer` file.

Expected filenames:
- AZ_IACA_Root.cer (SHA-256: <insert-sha256-here>)
- CA_IACA_Root.cer (SHA-256: <insert-sha256-here>)
- Federal_IACA_Root.cer (SHA-256: <insert-sha256-here>)

Replace the SHA-256 placeholders above with the fingerprints of the
actual production anchors once provisioned. The release build will fail
if no `.cer` files are present.
