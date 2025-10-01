# Structured telemetry samples

The staging build writes newline-delimited JSON to `files/telemetry/events.log`. Each entry mirrors
`StructuredEvent` and intentionally avoids subject-identifying fields.

```json
{"event":"verification_completed","timestamp_ms":1720000000000,"scan_duration_ms":428,"success":true,"reason_code":"OK","trust_stale":false}
{"event":"trust_list_refresh","timestamp_ms":1720000005123,"scan_duration_ms":987,"success":false,"reason_code":"SocketTimeoutException"}
```

* `event` — logical action name.
* `timestamp_ms` — when the operation started.
* `scan_duration_ms` — elapsed time in milliseconds.
* `success` — outcome flag.
* `reason_code` — success/failure reason code.
* `trust_stale` — whether the trust snapshot was stale when verifying.
