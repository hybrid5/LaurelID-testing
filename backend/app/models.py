"""SQLAlchemy models for events and aggregate statistics."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, Column, Date, DateTime, Integer, String, Text
from sqlalchemy.orm import declarative_base

Base = declarative_base()


class Event(Base):
    __tablename__ = "events"

    id = Column(Integer, primary_key=True, index=True)
    event_type = Column(String(64), index=True, nullable=False)
    user_id = Column(String(255), nullable=True)
    payload = Column(Text, nullable=False, default="{}")
    metadata = Column(Text, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False, index=True)
    anonymized = Column(Boolean, default=False, nullable=False)


class EventStat(Base):
    __tablename__ = "event_stats"

    id = Column(Integer, primary_key=True, index=True)
    event_type = Column(String(64), index=True, nullable=False)
    event_date = Column(Date, index=True, nullable=False)
    count = Column(Integer, default=0, nullable=False)
