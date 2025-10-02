"""FastAPI application entrypoint for the ingestion API."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import threading
from contextlib import suppress
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException, Query, Request, status
from sqlalchemy import Select, select
from sqlalchemy.orm import Session

from . import schemas
from .auth import verify_jwt
from .database import SessionLocal, engine
from .models import Base, Event, EventStat

logger = logging.getLogger(__name__)

Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="LaurelID Ingestion API",
    description="API for collecting kiosk events and retrieving aggregate statistics.",
    version="0.1.0",
)


def get_db() -> Session:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def anonymize_event(event: Event) -> None:
    event.user_id = None
    event.payload = "{}"
    event.metadata_json = None
    event.anonymized = True


def enforce_retention_policy(db: Session) -> None:
    cutoff = datetime.utcnow() - timedelta(days=30)
    stale_events: List[Event] = (
        db.execute(
            select(Event).where(Event.created_at < cutoff, Event.anonymized.is_(False))
        )
        .scalars()
        .all()
    )
    if not stale_events:
        return
    for event in stale_events:
        anonymize_event(event)
    db.flush()


def update_stats(db: Session, event: Event) -> None:
    event_date = event.created_at.date()
    stmt: Select[EventStat] = select(EventStat).where(
        EventStat.event_type == event.event_type,
        EventStat.event_date == event_date,
    )
    stat = db.execute(stmt).scalar_one_or_none()
    if stat is None:
        stat = EventStat(event_type=event.event_type, event_date=event_date, count=1)
        db.add(stat)
    else:
        stat.count += 1
    db.flush()


class RateLimitError(Exception):
    """Raised when a caller exceeds the configured rate limit."""


class FixedWindowRateLimiter:
    """Simple in-memory fixed window rate limiter keyed by identifier."""

    def __init__(self, max_requests: int, window_seconds: int) -> None:
        self._max_requests = max_requests
        self._window_seconds = window_seconds
        self._counters: dict[str, tuple[int, float]] = {}
        self._lock = threading.Lock()

    def check(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            count, window_start = self._counters.get(key, (0, now))
            if now - window_start >= self._window_seconds:
                count = 0
                window_start = now
            if count >= self._max_requests:
                raise RateLimitError(f"Rate limit exceeded for key {key}")
            self._counters[key] = (count + 1, window_start)


def _get_rate_limiter() -> FixedWindowRateLimiter:
    requests_per_window = int(os.environ.get("INGESTION_STATS_RATE_LIMIT", "60"))
    window_seconds = int(os.environ.get("INGESTION_STATS_RATE_WINDOW", "60"))
    return FixedWindowRateLimiter(requests_per_window, window_seconds)


_stats_rate_limiter = _get_rate_limiter()
_retention_task: Optional[asyncio.Task[None]] = None
_retention_interval_seconds = int(
    os.environ.get("INGESTION_RETENTION_INTERVAL_SECONDS", str(24 * 60 * 60))
)


def _apply_retention_once() -> None:
    with SessionLocal() as db:
        enforce_retention_policy(db)
        db.commit()


async def _run_retention_cycle() -> None:
    try:
        await asyncio.to_thread(_apply_retention_once)
    except Exception:  # pragma: no cover - log unexpected failures
        logger.exception("Failed to execute retention policy")


async def _retention_worker() -> None:
    try:
        while True:
            await _run_retention_cycle()
            await asyncio.sleep(_retention_interval_seconds)
    except asyncio.CancelledError:  # pragma: no cover - cooperative cancellation
        pass


def _start_retention_worker() -> None:
    global _retention_task
    if _retention_task is None:
        loop = asyncio.get_running_loop()
        _retention_task = loop.create_task(_retention_worker())


@app.post("/events", response_model=schemas.EventOut, status_code=status.HTTP_201_CREATED)
def ingest_event(
    event_in: schemas.EventIn,
    _: dict = Depends(verify_jwt),
    db: Session = Depends(get_db),
) -> schemas.EventOut:
    event = Event(
        event_type=event_in.event_type,
        user_id=event_in.user_id,
        payload=json.dumps(event_in.payload),
        metadata_json=json.dumps(event_in.metadata) if event_in.metadata is not None else None,
    )
    db.add(event)
    db.flush()

    if event.created_at is None:
        raise HTTPException(status_code=500, detail="Failed to persist event")

    update_stats(db, event)

    db.commit()
    db.refresh(event)
    return schemas.EventOut.from_orm(event)


@app.get("/stats", response_model=List[schemas.EventStatOut])
def list_stats(
    request: Request,
    page: int = Query(1, ge=1),
    page_size: int = Query(100, ge=1, le=500),
    _: dict = Depends(verify_jwt),
    db: Session = Depends(get_db),
) -> List[schemas.EventStatOut]:
    client_identifier = "anonymous"
    if request.client:
        client_identifier = request.client.host or client_identifier

    try:
        _stats_rate_limiter.check(client_identifier)
    except RateLimitError:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Rate limit exceeded")

    offset = (page - 1) * page_size
    stmt: Select[EventStat] = (
        select(EventStat)
        .order_by(EventStat.event_date.desc(), EventStat.event_type.asc())
        .offset(offset)
        .limit(page_size)
    )
    stats = db.execute(stmt).scalars().all()
    return [schemas.EventStatOut.from_orm(stat) for stat in stats]


@app.on_event("startup")
async def apply_retention_policy() -> None:
    _start_retention_worker()
    # Run once on startup to ensure old data is purged immediately.
    await _run_retention_cycle()


@app.on_event("shutdown")
async def stop_retention_policy() -> None:
    global _retention_task
    task = _retention_task
    if task is None:
        return
    task.cancel()
    with suppress(asyncio.CancelledError):
        await task
    _retention_task = None


def reset_application_state() -> None:
    """Reset mutable globals for test isolation."""

    global _stats_rate_limiter
    _stats_rate_limiter = _get_rate_limiter()
