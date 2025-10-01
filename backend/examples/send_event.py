"""Example client that posts a kiosk event to the ingestion API."""
from __future__ import annotations

from datetime import datetime

import requests

API_URL = "http://127.0.0.1:8000"
JWT_TOKEN = "replace-with-test-token"


def main() -> None:
    headers = {"Authorization": f"Bearer {JWT_TOKEN}"}
    payload = {
        "event_type": "kiosk.screen_view",
        "user_id": "visitor-123",
        "payload": {"screen": "welcome", "locale": "en-US"},
        "metadata": {"kiosk_id": "kiosk-01", "captured_at": datetime.utcnow().isoformat()},
    }
    response = requests.post(f"{API_URL}/events", headers=headers, json=payload)
    response.raise_for_status()
    print("Event stored:", response.json())

    stats = requests.get(f"{API_URL}/stats", headers=headers)
    stats.raise_for_status()
    print("Stats:", stats.json())


if __name__ == "__main__":
    main()
