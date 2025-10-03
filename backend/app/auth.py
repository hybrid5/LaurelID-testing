"""JWT authentication utilities."""
from __future__ import annotations

import os
import threading
from datetime import datetime, timezone
from functools import lru_cache
from typing import Dict

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

ALGORITHM = "RS256"
REQUIRED_CLAIMS = ("exp", "iss", "aud", "nonce")
INTEGRITY_CLAIM = "device_integrity"
REQUIRED_INTEGRITY_VERDICT = "MEETS_DEVICE_INTEGRITY"
auth_scheme = HTTPBearer(auto_error=False)


class NonceReplayError(Exception):
    """Raised when a nonce has already been observed."""


class NonceCache:
    """Tracks previously observed nonces to prevent replay attacks."""

    def __init__(self) -> None:
        self._entries: Dict[str, datetime] = {}
        self._lock = threading.Lock()

    def _purge(self, now: datetime) -> None:
        expired = [nonce for nonce, expiry in self._entries.items() if expiry <= now]
        for nonce in expired:
            self._entries.pop(nonce, None)

    def register(self, nonce: str, expires_at: datetime) -> None:
        now = datetime.now(timezone.utc)
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)

        with self._lock:
            self._purge(now)
            expiry = self._entries.get(nonce)
            if expiry and expiry > now:
                raise NonceReplayError(f"Nonce {nonce!r} has already been used")
            self._entries[nonce] = expires_at


_nonce_cache = NonceCache()


def _get_env_setting(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"{name} environment variable must be set to validate tokens.")
    return value


def _get_jwks_url() -> str:
    return _get_env_setting("INGESTION_JWT_JWKS_URL")


def _get_expected_issuer() -> str:
    return _get_env_setting("INGESTION_JWT_ISSUER")


def _get_expected_audience() -> str:
    return _get_env_setting("INGESTION_JWT_AUDIENCE")


@lru_cache(maxsize=1)
def _get_jwks_client(url: str) -> jwt.PyJWKClient:
    return jwt.PyJWKClient(url)


def _get_signing_key(token: str) -> jwt.PyJWK:
    jwks_client = _get_jwks_client(_get_jwks_url())
    return jwks_client.get_signing_key_from_jwt(token)


def verify_jwt(credentials: HTTPAuthorizationCredentials = Depends(auth_scheme)) -> Dict:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing token")

    token = credentials.credentials
    try:
        signing_key = _get_signing_key(token)
        payload = jwt.decode(
            token,
            signing_key.key,
            algorithms=[ALGORITHM],
            audience=_get_expected_audience(),
            issuer=_get_expected_issuer(),
            options={"require": list(REQUIRED_CLAIMS)},
        )
    except jwt.ExpiredSignatureError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token expired") from exc
    except jwt.InvalidAudienceError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid audience") from exc
    except jwt.InvalidIssuerError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid issuer") from exc
    except jwt.MissingRequiredClaimError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing claim") from exc
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token") from exc

    nonce = payload.get("nonce")
    exp = payload.get("exp")
    if nonce is None or exp is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing claim")

    verdict = payload.get(INTEGRITY_CLAIM)
    if verdict != REQUIRED_INTEGRITY_VERDICT:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Device integrity verification failed",
        )

    expires_at = datetime.fromtimestamp(exp, tz=timezone.utc)
    try:
        _nonce_cache.register(nonce, expires_at)
    except NonceReplayError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Nonce already used") from exc

    return payload


def reset_auth_state() -> None:
    """Reset cached authentication state. Intended for use in tests."""

    global _nonce_cache
    _get_jwks_client.cache_clear()
    _nonce_cache = NonceCache()
