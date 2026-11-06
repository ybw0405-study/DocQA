from __future__ import annotations

TOO_LARGE = "DOC-VALIDATION-001"
UNSUPPORTED_TYPE = "DOC-VALIDATION-002"
ENCRYPTED = "DOC-VALIDATION-003"
INVALID_DOCUMENT = "DOC-VALIDATION-004"
INTERNAL = "COM-INTERNAL-001"


class ParseJobError(Exception):
    """A sanitized, contract-ready parse failure. The message is safe to publish to Java."""

    def __init__(self, code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


class PublishError(Exception):
    """Raising this lets the broker redeliver; the vector write is idempotent."""


class VectorChangeError(Exception):
    """Sanitized vector-change failure, ready for the ``vector-change-completed`` ack.

    ``vector-change-completed`` has no ``retryable`` field: Java keeps the business state and lets
    the reconciliation sweep republish, so nothing here needs to express retry intent.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
