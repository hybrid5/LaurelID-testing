import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
import base64
import json

import jwt
import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

os.environ.setdefault("INGESTION_JWT_JWKS_URL", "https://jwks.example.com")
os.environ.setdefault("INGESTION_JWT_ISSUER", "https://issuer.example.com")
os.environ.setdefault("INGESTION_JWT_AUDIENCE", "laurel-audience")

from backend.app import auth  # noqa: E402  pylint: disable=wrong-import-position
from backend.app.auth import (  # noqa: E402  pylint: disable=wrong-import-position
    HTTPAuthorizationCredentials,
    HTTPException,
)

PRIMARY_PRIVATE_KEY = """-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDn2W5golZYSrR8
5q12ElvV7bxjgIxBq5kVlRmBneMMMTADJL4OnSSamSsFKFXtoNnvvSJGjsT97lgO
fxfTs7fHSDuhdkSY4n/yJ6lBr9ZRpohN3+dzVldQttofPBBeZFy+Ayu7WwqMICyj
DF3Hiz6Fu8kKjVTTe/AEdIoQsP6srdCQoUi7UM6pKNflPWKh22ezJyKoCBsNJS2f
nUa0rt+fmI5RIrFo6CXbvNrF87sokHKFgG/2jdDWUaPhOyd64rHKPDUKEqJUwSU6
aUyXyAuALnKQxfvTQkFY0Fof3w0WH2EOlDOQig7fz1G8sZJcpG/pEycNna09+wDu
7OuXnU9hAgMBAAECggEAEqs3SDR2La6ZRS/pcocUqj+WAnCaypxry5k5iC57lnMc
/bnPDHvykzjHZidK8QMZXWSuPn53RgezwzoQY/l2EEfElnIgoKtnWEtBhaKoKCO3
2MZxW4ANsJgCrQ5v9nvQHHRCqJfSe/lIwtnHcN6JWqkl7XG3LX7OszsjlWe9IBAJ
J2Zm5HqWjYk4ucSy/bcolBkzivDRsNsePHGGVWXIpFDanF3ighFklRRk35p68EXA
Yo+Ixbdav1KPy5u2ngIk/eF5sqyzsE52tcwmwDbUgJBZmBbzV1XDDSF7RT7rUDu7
eybXQ3gos7DoBUM2Q+gFLirTt7/xDTSioBj7VsOS2QKBgQD8fXxog6q1TV8E8T3/
w4t4+E0Bh30djnIa7kYTs2jimkuTGOp0LYdDR3fbUUj+LrdGPc4nnFlW5HfaiUia
y9AqNHtLQAelPvsLfNiLC21jxr7j6nCHEhoHiwM9XgifrXE7I57W4YHoA02tFZaC
z7wBhF4jOA0oOOR//aVtlWmIWQKBgQDrEn4Udrbjc1CtwGQuFhMiN9urOcmhS/2A
xiCOWXFnbm/bwWKvwdgnpHFvvDQy6aw4OqBlaRwn4RyJ0NyCKIaca1mqUrpvrJ/U
lk7xLkM50jhWckE68ILhc7Cg/nF3bNZ/xCuSSSZamADV47o5j75+vqWIiHdh2R8f
ZKuUgYMeSQKBgG1NmZTZIwZ3pyHJmbBmI7PLsfJuiABKkSUNb3LJ4Sbv6rWUPLLs
nrjGcKGWD8ZRzO9whBVrvtU59JS7h53Ti5spuxI5dtXXbPBtLIUM/l8KQ8sAy5P9
hx0q1c00LwGJIRKb+gBGAWnCPFcomE6qxVXWyrXuZuu5rVmnIX1OCDCJAoGAQIus
WNwb7ao9LehU2Z6wFEY4J/TPG13tNo2wZMXEcL0PM80O1umn+4KYrzCDOLOW2T2n
yxCobX7PQjw6P/b2tz52uWDL1lwU6t92v5yPUvIZDAuFQ7TEizkj96DF8R/Oafio
ahtxz5BLMm+8M4/3o3+fnjSyawieFhWjUzyYjxkCgYAgW+mlWBy5o2OEY36MwBmz
8ga+eAAVybwVQHHJPTFR8ke6mIrVSELLw3lDEDnAiN+ypJIaTUQAgPFow3X0VUYZ
geQwm8ClLvUraDfa5Mp0udxvuoCN4soDCmSrIujQzKUAycquhXXs4x+EgnnulElT
aIQuS6cQu9oFKNApxiNEUg==
-----END PRIVATE KEY-----"""

PRIMARY_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA59luYKJWWEq0fOatdhJb
1e28Y4CMQauZFZUZgZ3jDDEwAyS+Dp0kmpkrBShV7aDZ770iRo7E/e5YDn8X07O3
x0g7oXZEmOJ/8iepQa/WUaaITd/nc1ZXULbaHzwQXmRcvgMru1sKjCAsowxdx4s+
hbvJCo1U03vwBHSKELD+rK3QkKFIu1DOqSjX5T1iodtnsyciqAgbDSUtn51GtK7f
n5iOUSKxaOgl27zaxfO7KJByhYBv9o3Q1lGj4TsneuKxyjw1ChKiVMElOmlMl8gL
gC5ykMX700JBWNBaH98NFh9hDpQzkIoO389RvLGSXKRv6RMnDZ2tPfsA7uzrl51P
YQIDAQAB
-----END PUBLIC KEY-----"""

SECONDARY_PRIVATE_KEY = """-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDAiqZniU7HyHx7
/Xk3PD3mzu8ID4gepvd+z+Mpw8KHz7X3sBHQfcIxzfY7yMoL6A6wNKlv5Jd/5ynU
7aJDUtKxCovPdfTnk6dZhsY3iJFzGpQg0UCBJOAZV7Nxm/ZbWfeJBgMu7UApWlOE
gzOTiwitgWrZnkP+eV0j+fJgvOXeL1Mgt8RnJ28lAEh/kckQCmcI+uXbOfipL9au
FidYIM0ps3z7qtteBQullJtiUwPaS3+bJqrSnxUtqBtag7jcqrV4Cqpyde9foLCJ
74Xq8PHpJ6eynh2wKZzD6Snd8gLdjQW92IYYl68eaLiMFrQLuY9mUMKNCbIgfTL7
QBMapVkJAgMBAAECggEAA3pNpKecgmvK4dNzfc2rAZEBzMe5CfKynDoFjKLST/Eu
xH1L6RPobs1dUfmfuqTTpReiati6as9CWGv9ZxFnKFb8LQBgrtEiL/IJAQZIuEdF
3yzgaSTBHnwQy+/I/J/som83HgEfjE+rHAThqNvVSYsBotIiwMUw6z4vxFMNJNz2
cWIhtg1g/qPNOY2WQIZc3QSGpafhTiEiplmNLTBGz68Qs1i/bHEa+aKDhCvQlR7j
x1WWSZtFq7BSeUIZ4RO30I0dR7Dv1S7ff9DpAfOnjHd8G5zOXSxpUBrTpe89Sy1B
L7+5uBxG/BHJwK/9OdzW8AzQPN0H5LQ97keob9Dz/QKBgQDlWPT3ouAgvN0YLTXm
8lLEqHFPTZ5hWEgeI0Ses47hq23/tRs2/nesN1Lw1ec/Lls1mGFb/WnMlAZHVihN
9LucnQGSwEZbS2nHAtCiSQux34s+E7OZuut3Mb3Ejq/gCNZ16P8taa3stoL1btNF
ikSTuBImykaIAEwHQEs4b3Pt3QKBgQDW6rmkcrp0oISJLsU+tMBc7cfIJV0ZjsTX
ohPCHdlXJWod3n8KIzV6878elkwjn+NVzX3AwtAfCEBr0MMxjt37VOoYcOHF5sFH
xu//nTX4RDXxP/7nOgIw69V8LUaFI9rC3lRaLbKJuWxenKS20sHadElATD8wdGWV
zVHMx4wTHQKBgQDklo1SZJxPBO64shcPYGbua5TEHfDFxV/b6fry0rSOaHbybmf5
oBdXJq0cLZaWenWeLYqcTS+uH7tCTrVNPafgqPxwcAOv6rI7EKsxlOx7FPuLm8de
addWrdUem7jf6u8WBmyPrs0TKbXNOfCJVw6SzNwKnYE+/EzKzWIrlapOYQKBgAM8
l70lSS+Wd0iFnszZ9gewQRD/lw5aexZwR3Hl9y77zkRS5IDnlNecMiWox87Fiqvx
I1Ky3GWLP0UgaMAnUaqGVdw2XwAXAJQvJ9AmsvhhNprChvk+g2fvNVDgca5xosrK
hGSzSXwPgdVO8KAcPnUmyS+htlXpetottGysGKz1AoGBANobx5cLPr/mOX/8WvCn
yMqBnqs3z1vjk5Hk8431FLoryHZ3sr/iga+dZmvyTkEUdFYlSDqAHZoDfmhqJ5Wg
PdjZH0sd92hjbQq5IsO3HNruy0Dz9lSYeMYfZlwUXZkVD5uNkz3EMG8YYcp5gqDC
JOVbqrL6ia5PZnDoOhXclKj4
-----END PRIVATE KEY-----"""

SECONDARY_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwIqmZ4lOx8h8e/15Nzw9
5s7vCA+IHqb3fs/jKcPCh8+197AR0H3CMc32O8jKC+gOsDSpb+SXf+cp1O2iQ1LS
sQqLz3X055OnWYbGN4iRcxqUINFAgSTgGVezcZv2W1n3iQYDLu1AKVpThIMzk4sI
rYFq2Z5D/nldI/nyYLzl3i9TILfEZydvJQBIf5HJEApnCPrl2zn4qS/WrhYnWCDN
KbN8+6rbXgULpZSbYlMD2kt/myaq0p8VLagbWoO43Kq1eAqqcnXvX6Cwie+F6vDx
6Sensp4dsCmcw+kp3fIC3Y0FvdiGGJevHmi4jBa0C7mPZlDCjQmyIH0y+0ATGqVZ
CQIDAQAB
-----END PUBLIC KEY-----"""


def _b64encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64decode(data: str) -> bytes:
    padding = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + padding)


@pytest.fixture(autouse=True)
def reset_auth(monkeypatch):
    public_keys = {
        "primary": PRIMARY_PUBLIC_KEY,
        "secondary": SECONDARY_PUBLIC_KEY,
    }

    def get_signing_key(token: str):
        kid = jwt.get_unverified_header(token)["kid"]
        return SimpleNamespace(key=public_keys[kid])

    monkeypatch.setattr(auth, "_get_signing_key", get_signing_key)

    def fake_decode(
        token: str,
        key,
        algorithms=None,
        audience=None,
        issuer=None,
        options=None,
        **_,
    ):
        algorithms = algorithms or []
        header_b64, payload_b64, _signature = token.split(".")
        header = json.loads(_b64decode(header_b64))
        if algorithms and header.get("alg") not in algorithms:
            raise jwt.InvalidAlgorithmError("Invalid algorithm")

        payload = json.loads(_b64decode(payload_b64))
        required = (options or {}).get("require", [])
        for claim in required:
            if claim not in payload:
                raise jwt.MissingRequiredClaimError(claim)

        if issuer is not None and payload.get("iss") != issuer:
            raise jwt.InvalidIssuerError("Invalid issuer")

        if audience is not None:
            aud_claim = payload.get("aud")
            if isinstance(audience, (list, tuple, set)):
                valid = False
                if isinstance(aud_claim, str):
                    valid = aud_claim in audience
                elif isinstance(aud_claim, (list, tuple, set)):
                    valid = bool(set(audience) & set(aud_claim))
            else:
                valid = aud_claim == audience
            if not valid:
                raise jwt.InvalidAudienceError("Invalid audience")

        exp = payload.get("exp")
        if exp is not None and exp < int(datetime.now(timezone.utc).timestamp()):
            raise jwt.ExpiredSignatureError("Token expired")

        return payload

    monkeypatch.setattr(auth.jwt, "decode", fake_decode)
    auth.reset_auth_state()

    yield {
        "primary": PRIMARY_PRIVATE_KEY,
        "secondary": SECONDARY_PRIVATE_KEY,
    }

    auth.reset_auth_state()


def _build_token(
    private_key,
    *,
    nonce="abc",
    issuer=None,
    audience=None,
    lifetime_seconds=300,
    kid="primary",
    integrity_verdict="MEETS_DEVICE_INTEGRITY",
):
    now = datetime.now(timezone.utc)
    payload = {
        "iss": issuer or os.environ["INGESTION_JWT_ISSUER"],
        "aud": audience or os.environ["INGESTION_JWT_AUDIENCE"],
        "exp": int((now + timedelta(seconds=lifetime_seconds)).timestamp()),
        "nonce": nonce,
        "sub": "integration-test",
    }
    if integrity_verdict is not None:
        payload["device_integrity"] = integrity_verdict
    header = {"alg": "RS256", "typ": "JWT", "kid": kid}
    segments = [_b64encode(json.dumps(header).encode()), _b64encode(json.dumps(payload).encode())]
    segments.append(_b64encode(f"signed-with-{kid}".encode()))
    return ".".join(segments)


def test_verify_jwt_accepts_valid_token(reset_auth):
    private_keys = reset_auth
    token = _build_token(private_keys["primary"])
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)

    payload = auth.verify_jwt(credentials)
    assert payload["sub"] == "integration-test"


def test_verify_jwt_rejects_replayed_nonce(reset_auth):
    private_keys = reset_auth
    token = _build_token(private_keys["primary"], nonce="unique")
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)

    auth.verify_jwt(credentials)
    with pytest.raises(HTTPException) as excinfo:
        auth.verify_jwt(credentials)
    assert excinfo.value.status_code == 401
    assert "Nonce" in excinfo.value.detail


def test_verify_jwt_rejects_invalid_audience(reset_auth):
    private_keys = reset_auth
    token = _build_token(private_keys["primary"], audience="other-audience")
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)

    with pytest.raises(HTTPException) as excinfo:
        auth.verify_jwt(credentials)
    assert excinfo.value.status_code == 401
    assert "audience" in excinfo.value.detail.lower()


def test_verify_jwt_supports_key_rotation(reset_auth):
    private_keys = reset_auth

    first_token = _build_token(private_keys["primary"], nonce="nonce1")
    second_token = _build_token(private_keys["secondary"], nonce="nonce2", kid="secondary")

    auth.verify_jwt(HTTPAuthorizationCredentials(scheme="Bearer", credentials=first_token))
    auth.verify_jwt(HTTPAuthorizationCredentials(scheme="Bearer", credentials=second_token))


def test_verify_jwt_rejects_failed_integrity(reset_auth):
    private_keys = reset_auth
    token = _build_token(private_keys["primary"], integrity_verdict="FAILED_DEVICE_INTEGRITY")
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)

    with pytest.raises(HTTPException) as excinfo:
        auth.verify_jwt(credentials)
    assert excinfo.value.status_code == 401
    assert "integrity" in excinfo.value.detail.lower()


def test_verify_jwt_requires_integrity_claim(reset_auth):
    private_keys = reset_auth
    token = _build_token(private_keys["primary"], integrity_verdict=None)
    credentials = HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)

    with pytest.raises(HTTPException) as excinfo:
        auth.verify_jwt(credentials)
    assert excinfo.value.status_code == 401
    assert "integrity" in excinfo.value.detail.lower()
