from __future__ import annotations

import asyncio
import ipaddress
import socket
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit

_ALLOWED_SCHEMES = frozenset({"http", "https"})
_MAX_URL_CHARS = 2048
_MAX_HOST_CHARS = 253
# D-141：预检只拒绝「解析结果含内网地址」的确定形态；连接后的对端地址由
# client.py 的 `_connected_peer` 再校验一次，封住 DNS rebinding 的 TOCTOU（S7b3-M2）。
_FORBIDDEN_HOST_SUFFIXES = (".localhost", ".local", ".internal", ".home.arpa")
_FORBIDDEN_HOSTS = frozenset({"localhost"})
Resolver = Callable[[str, int], Awaitable[Sequence[str]]]


class UrlRejected(Exception):
    """The URL is structurally invalid, out of scope, or resolves into a forbidden network."""


@dataclass(frozen=True)
class TargetUrl:
    url: str
    scheme: str
    host: str
    port: int

    @property
    def origin(self) -> str:
        default = 443 if self.scheme == "https" else 80
        netloc = f"[{self.host}]" if ":" in self.host else self.host
        return f"{self.scheme}://{netloc}" if self.port == default else f"{self.scheme}://{netloc}:{self.port}"


async def _system_resolver(host: str, port: int) -> Sequence[str]:
    loop = asyncio.get_running_loop()
    infos = await loop.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return tuple({str(info[4][0]) for info in infos})


def is_forbidden_address(raw: str) -> bool:
    """Return true for loopback / private / link-local / reserved / multicast addresses.

    Also used on the *connected* peer address, so a DNS rebinding answer cannot
    slip a private target past the pre-flight resolution (S7b3-M2).
    """

    try:
        address = ipaddress.ip_address(raw)
    except ValueError:
        return True
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        address = address.ipv4_mapped
    return (
        address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_multicast
        or address.is_reserved
        or address.is_unspecified
    )


def _host_allowed(host: str, allowed_hosts: Sequence[str]) -> bool:
    if not allowed_hosts:
        return True
    return any(host == entry or host.endswith(f".{entry}") for entry in allowed_hosts)


async def validate_url(
    url: str,
    *,
    allowed_hosts: Sequence[str] = (),
    allow_private: bool = False,
    resolver: Resolver | None = None,
) -> TargetUrl:
    """Reject anything that is not a plain public http(s) address.

    ``allow_private`` exists for operator-configured endpoints (the self-hosted SearXNG
    instance), never for model-supplied URLs. ``resolver=None`` means the system
    resolver: ``BoundedHttpClient`` passes its unset default through, so an explicit
    ``None`` must not disable DNS checks (S7b-6-H1).
    """

    resolve = resolver or _system_resolver
    raw = url.strip()
    if not raw or len(raw) > _MAX_URL_CHARS:
        raise UrlRejected("URL is empty or too long")
    parts = urlsplit(raw)
    scheme = parts.scheme.lower()
    if scheme not in _ALLOWED_SCHEMES:
        raise UrlRejected("only http and https URLs are allowed")
    if parts.username or parts.password:
        raise UrlRejected("URLs with embedded credentials are not allowed")
    host = (parts.hostname or "").rstrip(".").lower()
    if not host or len(host) > _MAX_HOST_CHARS:
        raise UrlRejected("URL has no usable host")
    if host in _FORBIDDEN_HOSTS or host.endswith(_FORBIDDEN_HOST_SUFFIXES):
        raise UrlRejected("URL host is not routable")
    try:
        port = parts.port or (443 if scheme == "https" else 80)
    except ValueError as exc:
        raise UrlRejected("URL port is invalid") from exc
    if not _host_allowed(host, allowed_hosts):
        raise UrlRejected("URL host is outside the configured allowlist")

    if not allow_private:
        addresses: Sequence[str]
        try:
            literal = ipaddress.ip_address(host)
        except ValueError:
            try:
                addresses = await resolve(host, port)
            except (OSError, socket.gaierror) as exc:
                raise UrlRejected("URL host does not resolve") from exc
            if not addresses:
                raise UrlRejected("URL host does not resolve") from None
        else:
            addresses = (str(literal),)
        if any(is_forbidden_address(address) for address in addresses):
            raise UrlRejected("URL resolves to a private or reserved address")

    netloc_host = f"[{host}]" if ":" in host else host
    netloc = netloc_host if parts.port is None else f"{netloc_host}:{port}"
    return TargetUrl(
        url=urlunsplit((scheme, netloc, parts.path or "/", parts.query, "")),
        scheme=scheme,
        host=host,
        port=port,
    )


def host_matches(host: str, allowed_hosts: Sequence[str]) -> bool:
    return _host_allowed(host, allowed_hosts)
