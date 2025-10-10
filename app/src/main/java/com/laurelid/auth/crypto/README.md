# HPKE key handling

The HPKE recipient stack never extracts raw private key material from the Android
KeyStore. Production builds operate exclusively on `PrivateKey` handles. The
recipient context derives the shared secret by delegating to `KeyAgreement`
and feeds it into a local HPKE key schedule, allowing StrongBox-backed keys to
remain non-exportable. Debug builds may install in-memory X25519 keys guarded by
`BuildConfig.DEBUG` for interoperability with test vectors.
