"""Application package bootstrap utilities."""
from __future__ import annotations

import inspect
import typing


def _ensure_typing_compatibility() -> None:
    """Backport Python 3.11 ForwardRef behaviour for older dependencies."""

    forward_ref_evaluate = getattr(typing.ForwardRef, "_evaluate", None)
    if forward_ref_evaluate is None:
        return

    signature = inspect.signature(forward_ref_evaluate)
    params = signature.parameters
    recursive_param = params.get("recursive_guard")
    if recursive_param is None:
        return
    if recursive_param.default is not inspect._empty:
        return

    original_evaluate = forward_ref_evaluate

    def _patched_evaluate(self, globalns, localns, *args, **kwargs):  # type: ignore[override]
        kwargs.setdefault("recursive_guard", set())
        return original_evaluate(self, globalns, localns, *args, **kwargs)

    typing.ForwardRef._evaluate = _patched_evaluate  # type: ignore[attr-defined]


_ensure_typing_compatibility()
