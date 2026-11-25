from __future__ import annotations

from typing import Protocol

from minio import Minio
from minio.error import S3Error


class StorageError(Exception):
    pass


class ObjectNotFound(StorageError):
    pass


class ObjectTooLarge(StorageError):
    pass


class StorageUnavailable(StorageError):
    pass


class ObjectStorage(Protocol):
    def get(self, object_key: str, *, max_bytes: int) -> bytes: ...


class MinioObjectStorage:
    """Read-only MinIO access. Credentials come from the environment and are never logged."""

    def __init__(
        self,
        endpoint: str,
        access_key: str,
        secret_key: str,
        bucket: str,
        *,
        secure: bool = False,
        client: Minio | None = None,
    ) -> None:
        normalized = endpoint.rstrip("/")
        if normalized.lower().startswith("http://"):
            normalized = normalized[7:]
            secure = False
        elif normalized.lower().startswith("https://"):
            normalized = normalized[8:]
            secure = True
        self._bucket = bucket
        self._client = client or Minio(
            normalized,
            access_key=access_key,
            secret_key=secret_key,
            secure=secure,
        )

    def get(self, object_key: str, *, max_bytes: int) -> bytes:
        response = None
        try:
            response = self._client.get_object(self._bucket, object_key)
            data = response.read(max_bytes + 1)
        except S3Error as exc:
            if exc.code in {"NoSuchKey", "NoSuchObject"}:
                raise ObjectNotFound("object does not exist") from exc
            raise StorageUnavailable("object storage read failed") from exc
        except Exception as exc: # noqa: BLE001 - urllib3/minio transport errors vary
            raise StorageUnavailable("object storage read failed") from exc
        finally:
            if response is not None:
                response.close()
                response.release_conn()
        if len(data) > max_bytes:
            raise ObjectTooLarge("object exceeds the configured limit")
        return data


class InMemoryObjectStorage:
    """Deterministic storage double for tests."""

    def __init__(self, objects: dict[str, bytes] | None = None) -> None:
        self._objects = dict(objects or {})

    def put(self, object_key: str, data: bytes) -> None:
        self._objects[object_key] = data

    def get(self, object_key: str, *, max_bytes: int) -> bytes:
        try:
            data = self._objects[object_key]
        except KeyError as exc:
            raise ObjectNotFound("object does not exist") from exc
        if len(data) > max_bytes:
            raise ObjectTooLarge("object exceeds the configured limit")
        return data
