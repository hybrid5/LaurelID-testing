"""FastAPI application entrypoint for the ingestion API."""
from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import List

from fastapi import Depends, FastAPI, HTTPException, status
from sqlalchemy import Select, select
from sqlalchemy.orm import Session

from . import schemas
from .auth import verify_jwt
from .database import SessionLocal, engine
from .models import Base, Event, EventStat

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
    event.metadata = None
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
        metadata=json.dumps(event_in.metadata) if event_in.metadata is not None else None,
    )
    db.add(event)
    db.flush()

    if event.created_at is None:
        raise HTTPException(status_code=500, detail="Failed to persist event")

    update_stats(db, event)
    enforce_retention_policy(db)

    db.commit()
    db.refresh(event)
    return schemas.EventOut.from_orm(event)


@app.get("/stats", response_model=List[schemas.EventStatOut])
def list_stats(
    limit: int = 100,
    _: dict = Depends(verify_jwt),
    db: Session = Depends(get_db),
) -> List[schemas.EventStatOut]:
    stmt: Select[EventStat] = (
        select(EventStat)
        .order_by(EventStat.event_date.desc(), EventStat.event_type.asc())
        .limit(limit)
    )
    stats = db.execute(stmt).scalars().all()
    return [schemas.EventStatOut.from_orm(stat) for stat in stats]


@app.on_event("startup")
def apply_retention_policy() -> None:
    with SessionLocal() as db:
        enforce_retention_policy(db)
        db.commit()
