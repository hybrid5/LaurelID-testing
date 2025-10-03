import sys
from importlib import reload
from pathlib import Path

import sys
import sys
from importlib import reload
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.app import schemas
from backend.app.auth import HTTPException


class DummyClient:
    def __init__(self, host: str) -> None:
        self.host = host


class DummyRequest:
    def __init__(self, host: str) -> None:
        self.client = DummyClient(host)


@pytest.fixture
def app_module(tmp_path, monkeypatch):
    db_path = tmp_path / "ingestion.db"
    monkeypatch.setenv("INGESTION_DATABASE_URL", f"sqlite:///{db_path}")
    monkeypatch.setenv("INGESTION_STATS_RATE_LIMIT", "2")
    monkeypatch.setenv("INGESTION_STATS_RATE_WINDOW", "60")
    monkeypatch.setenv("INGESTION_RETENTION_INTERVAL_SECONDS", "3600")

    from backend.app import database

    reload(database)

    from backend.app import main

    reload(main)
    main.reset_application_state()

    yield main

    main.reset_application_state()


def _create_event(main, db_session, event_type: str, user_id: str = "user") -> None:
    event_in = schemas.EventIn(event_type=event_type, user_id=user_id, payload={"idx": event_type})
    main.ingest_event(event_in, {}, db_session)


def test_stats_pagination_returns_expected_page(app_module):
    main = app_module
    from backend.app import database

    with database.SessionLocal() as session:
        event_types = [f"event-{idx}" for idx in range(5)]
        for event_type in event_types:
            _create_event(main, session, event_type)

        request = DummyRequest("tester")
        stats = main.list_stats(request=request, page=1, page_size=2, _={}, db=session)

    expected = sorted(event_types)[:2]
    assert [entry.event_type for entry in stats] == expected


def test_stats_rate_limit_enforced(app_module):
    main = app_module
    from backend.app import database

    with database.SessionLocal() as session:
        request = DummyRequest("rate-limit")
        main.list_stats(request=request, page=1, page_size=100, _={}, db=session)
        main.list_stats(request=request, page=1, page_size=100, _={}, db=session)
        with pytest.raises(HTTPException) as excinfo:
            main.list_stats(request=request, page=1, page_size=100, _={}, db=session)

    assert excinfo.value.status_code == 429
    assert excinfo.value.detail == "Rate limit exceeded"

    main.reset_application_state()
