class DeadlineExceeded(Exception):
    """Raised when the request-level monotonic deadline has expired."""


class PlannerProtocolError(Exception):
    """Raised when a planner output violates the tool or answer schema."""


class PiiLeakError(Exception):
    """Raised when the final prompt gate still detects unmasked PII."""

    def __init__(self, categories: tuple[str, ...], count: int) -> None:
        super().__init__("final prompt gate detected PII")
        self.categories = categories
        self.count = count
