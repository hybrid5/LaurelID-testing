# LaurelID Ingestion API

This FastAPI service provides a minimal backend for ingesting kiosk events, persisting
aggregate statistics, and enforcing a 30-day anonymization policy.

## Features

- `POST /events` endpoint accepts JSON events once authenticated with a JWT bearer token.
- Automatically maintains daily aggregate counts per event type for reporting purposes.
- Enforces a retention policy that anonymizes events older than 30 days (user identifiers,
  payloads, and metadata are wiped while keeping timestamp and type for auditing).
- Ships with an OpenAPI specification (`openapi.yaml`) and a demo client for local testing.

## Prerequisites

- Python 3.10+
- A virtual environment is recommended.
- Configure the authentication environment variables expected by the API:
  - `INGESTION_JWT_JWKS_URL` – HTTPS URL where the RSA public keys (JWKS) are hosted.
  - `INGESTION_JWT_ISSUER` – Expected issuer claim in incoming JWTs.
  - `INGESTION_JWT_AUDIENCE` – Expected audience claim in incoming JWTs.

## Installation

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Running the server

```bash
export INGESTION_JWT_JWKS_URL="https://auth.example.com/.well-known/jwks.json"
export INGESTION_JWT_ISSUER="https://auth.example.com/"
export INGESTION_JWT_AUDIENCE="laurel-ingestion"
uvicorn app.main:app --reload
```

The API will be available at http://127.0.0.1:8000. Interactive API documentation is served
at `/docs` (Swagger UI) and `/redoc`.

## Generating tokens for testing

Authentication relies on RS256-signed JWTs whose public keys are served from the configured
JWKS endpoint. For local testing you can generate a key pair and host the JWKS with a mock
server. The snippet below shows how to mint a token using `pyjwt` and an RSA private key that
corresponds to the JWKS entry (the `kid` header must match):

```python
import jwt
from pathlib import Path

private_key = Path("private.pem").read_text()

claims = {
    "sub": "kiosk-client-1",
    "aud": "laurel-ingestion",
    "iss": "https://auth.example.com/",
    "nonce": "unique-request-id",
    "device_integrity": "MEETS_DEVICE_INTEGRITY",
}

token = jwt.encode(claims, private_key, algorithm="RS256", headers={"kid": "primary"})
print(token)
```

## Sample client

A minimal integration example is provided under `examples/send_event.py`. Supply the API URL
and a valid JWT via CLI arguments or environment variables before running it:

```bash
python examples/send_event.py --api-url http://127.0.0.1:8000 --token "<jwt>"
# or set INGESTION_API_URL / INGESTION_JWT_TOKEN and call python examples/send_event.py
```

## Database

By default the service uses a local SQLite file (`ingestion.db`). To use another database,
set the `INGESTION_DATABASE_URL` environment variable to a valid SQLAlchemy connection string.

## Retention policy

On every event ingestion and at application startup, the service anonymizes records older
than 30 days by clearing sensitive columns. Aggregate statistics are preserved independently,
so historical reporting remains available even after anonymization.

## OpenAPI specification

The published API contract is tracked in `openapi.yaml`. It mirrors the schema served by the
running FastAPI application.
