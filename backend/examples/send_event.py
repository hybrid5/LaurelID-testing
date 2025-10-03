"""Example client that posts a kiosk event to the ingestion API."""
from __future__ import annotations

import argparse
import os
from datetime import datetime

import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Send a sample kiosk event")
    parser.add_argument(
        "--api-url",
        default=os.environ.get("INGESTION_API_URL", "http://127.0.0.1:8000"),
        help="Ingestion API base URL (default: %(default)s or INGESTION_API_URL)",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("INGESTION_JWT_TOKEN"),
        help="Bearer token used to authenticate the request (INGESTION_JWT_TOKEN)",
    )
    args = parser.parse_args()
    if not args.token:
        parser.error("A JWT must be supplied via --token or INGESTION_JWT_TOKEN")
    return args


def main() -> None:
    args = parse_args()
    headers = {"Authorization": f"Bearer {args.token}"}
    payload = {
        "event_type": "kiosk.screen_view",
        "user_id": "visitor-123",
        "payload": {"screen": "welcome", "locale": "en-US"},
        "metadata": {"kiosk_id": "kiosk-01", "captured_at": datetime.utcnow().isoformat()},
    }
    response = requests.post(f"{args.api_url}/events", headers=headers, json=payload, timeout=10)
    response.raise_for_status()
    print("Event stored:", response.json())

    stats = requests.get(f"{args.api_url}/stats", headers=headers, timeout=10)
    stats.raise_for_status()
    print("Stats:", stats.json())


if __name__ == "__main__":
    main()
