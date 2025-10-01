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
- Set the `INGESTION_JWT_SECRET` environment variable to the shared secret used for JWT
  signature verification.

## Installation

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Running the server

```bash
export INGESTION_JWT_SECRET="super-secret-key"
uvicorn app.main:app --reload
```

The API will be available at http://127.0.0.1:8000. Interactive API documentation is served
at `/docs` (Swagger UI) and `/redoc`.

## Generating tokens for testing

The API expects clients to send a bearer token signed with the same secret and using the
`HS256` algorithm. The payload can contain any claims required by the kiosk network. Below is
an example using Python and `pyjwt`:

```python
import jwt

secret = "super-secret-key"
claims = {"sub": "kiosk-client-1"}
token = jwt.encode(claims, secret, algorithm="HS256")
print(token)
```

## Sample client

A minimal integration example is provided under `examples/send_event.py`. Configure the
`API_URL` and `JWT_TOKEN` constants before running it:

```bash
python examples/send_event.py
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
