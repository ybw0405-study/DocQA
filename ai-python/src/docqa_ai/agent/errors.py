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


class CheckpointBusyError(Exception):
    """Raised when the per-conversation checkpoint lock is held (or Redis is unreachable).

    同一会话的续跑必须单飞：两个请求同时重入同一个 thread 会重复执行工具、重复花费。
    映射到既有 upstream_unavailable，不新增错误码。
    """
