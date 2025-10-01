"""Pydantic models for request and response bodies."""
from __future__ import annotations

from datetime import date, datetime
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class EventIn(BaseModel):
    event_type: str = Field(..., description="Type of the event, e.g. kiosk.viewed")
    user_id: Optional[str] = Field(None, description="Identifier of the user interacting with the kiosk")
    payload: Dict[str, Any] = Field(default_factory=dict, description="Structured event payload")
    metadata: Optional[Dict[str, Any]] = Field(
        default=None,
        description="Optional metadata about the event, such as kiosk ID or location.",
    )


class EventOut(BaseModel):
    id: int
    event_type: str
    created_at: datetime
    anonymized: bool

    class Config:
        orm_mode = True


class EventStatOut(BaseModel):
    event_type: str
    event_date: date
    count: int

    class Config:
        orm_mode = True
