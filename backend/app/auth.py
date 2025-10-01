"""JWT authentication utilities."""
from __future__ import annotations

import os
from typing import Dict

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

ALGORITHM = "HS256"
auth_scheme = HTTPBearer(auto_error=False)


def _get_secret() -> str:
    secret = os.environ.get("INGESTION_JWT_SECRET")
    if not secret:
        raise RuntimeError(
            "INGESTION_JWT_SECRET environment variable must be set to validate tokens."
        )
    return secret


def verify_jwt(credentials: HTTPAuthorizationCredentials = Depends(auth_scheme)) -> Dict:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")

    token = credentials.credentials
    try:
        payload = jwt.decode(token, _get_secret(), algorithms=[ALGORITHM])
    except jwt.ExpiredSignatureError as exc:  # pragma: no cover - explicit handling
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token expired") from exc
    except jwt.PyJWTError as exc:  # pragma: no cover - explicit handling
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token") from exc
    return payload
