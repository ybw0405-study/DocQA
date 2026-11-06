from __future__ import annotations

import json
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from urllib.parse import urljoin, urlsplit

import httpx

from docqa_ai.tools.web.robots import MAX_ROBOTS_BYTES, RobotsPolicy, robots_allowed
from docqa_ai.tools.web.urls import (
    Resolver,
    UrlRejected,
    is_forbidden_address,
    validate_url,
)

_REDIRECT_STATUSES = frozenset({301, 302, 303, 307, 308})
_MAX_REDIRECTS = 2
_ROBOTS_TTL_SECONDS = 3600.0


class FetchError(Exception):
    """Base class for every failure a web tool may surface as a ToolError."""


class FetchRejected(FetchError):
    """SSRF, allowlist or robots policy refused the request."""


class FetchUnavailable(FetchError):
    """Transport failed or the peer is unreachable."""


class FetchTimeout(FetchError):
    """The request exceeded the configured or remaining deadline."""


class PayloadTooLarge(FetchError):
    """The response body exceeded the configured byte ceiling."""


@dataclass(frozen=True)
class HttpResponse:
    status: int
    url: str
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    truncated: bool = False

    def header(self, name: str) -> str:
        return self.headers.get(name.lower(), "")


@dataclass(frozen=True)
class _RobotsEntry:
    expires_at: float
    policy: RobotsPolicy | None


_DENY_ALL = RobotsPolicy.parse("User-agent: *\nDisallow: /\n")
_ALLOW_ALL = RobotsPolicy()


def _connected_peer(response: httpx.Response) -> str | None:
    """Return the address actually connected to, or None when the transport has none.

    ``MockTransport`` exposes no ``network_stream``; real httpcore connections do.
    """

    stream = response.extensions.get("network_stream")
    if stream is None:
        return None
    try:
        address = stream.get_extra_info("server_addr")
    except Exception:  # pragma: no cover - backend specific
        return None
    if isinstance(address, tuple) and address:
        return str(address[0])
    return str(address) if address else None


class BoundedHttpClient:
    """Bounded outbound HTTP for built-in tools (D-141 / D-147).

    Every request goes through URL validation (public address, allowlist, no
    embedded credentials), robots.txt for model-supplied pages, a byte ceiling,
    a timeout and a redirect budget that revalidates each hop.
    """

    def __init__(
        self,
        *,
        timeout_s: float = 5.0,
        max_bytes: int = 524288,
        allowed_hosts: Sequence[str] = (),
        user_agent: str = "docqa/1.0",
        allow_private: bool = False,
        robots_ttl_s: float = _ROBOTS_TTL_SECONDS,
        # None = 系统解析器；validate_url 内部回退，不能把 None 当成「跳过 DNS 校验」。
        resolver: Resolver | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        clock=time.monotonic,
        on_cache_hit: Callable[[], None] | None = None,
    ) -> None:
        if timeout_s <= 0:
            raise ValueError("web timeout must be positive")
        if max_bytes <= 0:
            raise ValueError("web byte ceiling must be positive")
        self._timeout_s = timeout_s
        self._max_bytes = max_bytes
        self._allowed_hosts = tuple(host.lower() for host in allowed_hosts)
        self._user_agent = user_agent
        self._allow_private = allow_private
        self._robots_ttl_s = max(0.0, robots_ttl_s)
        self._resolver = resolver
        self._clock = clock
        self._on_cache_hit = on_cache_hit
        self._robots: dict[str, _RobotsEntry] = {}
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_s),
            follow_redirects=False,
            transport=transport,
            headers={"User-Agent": user_agent, "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"},
        )

    async def get(
        self,
        url: str,
        *,
        params: dict[str, object] | None = None,
        headers: dict[str, str] | None = None,
        check_robots: bool = True,
        allow_private: bool | None = None,
        allowed_hosts: Sequence[str] | None = None,
        timeout_s: float | None = None,
    ) -> HttpResponse:
        return await self._request(
            "GET",
            url,
            params=params,
            content=None,
            headers=headers,
            check_robots=check_robots,
            allow_private=self._allow_private if allow_private is None else allow_private,
            allowed_hosts=self._allowed_hosts if allowed_hosts is None else allowed_hosts,
            timeout_s=self._timeout_s if timeout_s is None else timeout_s,
        )

    async def post_json(
        self,
        url: str,
        payload: dict[str, object],
        *,
        headers: dict[str, str] | None = None,
        allow_private: bool | None = None,
        allowed_hosts: Sequence[str] | None = None,
        timeout_s: float | None = None,
    ) -> HttpResponse:
        merged = {"Content-Type": "application/json", **(headers or {})}
        return await self._request(
            "POST",
            url,
            params=None,
            content=payload,
            headers=merged,
            check_robots=False,
            allow_private=self._allow_private if allow_private is None else allow_private,
            allowed_hosts=self._allowed_hosts if allowed_hosts is None else allowed_hosts,
            timeout_s=self._timeout_s if timeout_s is None else timeout_s,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _request(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, object] | None,
        content: object | None,
        headers: dict[str, str] | None,
        check_robots: bool,
        allow_private: bool,
        allowed_hosts: Sequence[str],
        timeout_s: float,
        redirects_left: int = _MAX_REDIRECTS,
    ) -> HttpResponse:
        if timeout_s <= 0:
            raise FetchTimeout("no time left for the request")
        try:
            target = await validate_url(
                url,
                allowed_hosts=allowed_hosts,
                allow_private=allow_private,
                resolver=self._resolver,
            )
        except UrlRejected as exc:
            raise FetchRejected(str(exc)) from exc
        if check_robots:
            policy = await self._robots_policy(target.origin, allow_private, allowed_hosts)
            parts = urlsplit(target.url)
            path = parts.path + (f"?{parts.query}" if parts.query else "")
            if not robots_allowed(policy, path):
                raise FetchRejected("robots.txt disallows this path")

        request = self._client.build_request(
            method,
            target.url,
            params=params,
            content=self._encode(content),
            headers=headers,
        )
        request.extensions["timeout"] = httpx.Timeout(timeout_s).as_dict()
        try:
            response = await self._client.send(request, stream=True, follow_redirects=False)
        except httpx.TimeoutException as exc:
            raise FetchTimeout("request timed out") from exc
        except httpx.HTTPError as exc:
            raise FetchUnavailable("request failed") from exc

        try:
            # S7b3-M2：预检解析与真正连接是两次解析，这里用实际连上的对端地址
            # 再判一次，封住 DNS rebinding 的 TOCTOU 窗口（每一跳都过这里）。
            if not allow_private:
                peer = _connected_peer(response)
                if peer is not None and is_forbidden_address(peer):
                    raise FetchRejected("connection peer is a private or reserved address")
            location = response.headers.get("location")
            if response.status_code in _REDIRECT_STATUSES and location:
                if redirects_left <= 0:
                    raise FetchRejected("too many redirects")
                next_url = urljoin(target.url, location)
                if target.scheme == "https" and urlsplit(next_url).scheme.lower() == "http":
                    raise FetchRejected("redirect downgrades https to http")
                await response.aclose()
                use_get = response.status_code == 303
                return await self._request(
                    "GET" if use_get else method,
                    next_url,
                    params=params if method == "GET" else None,
                    content=None if use_get else content,
                    headers=headers,
                    check_robots=check_robots,
                    allow_private=allow_private,
                    allowed_hosts=allowed_hosts,
                    timeout_s=timeout_s,
                    redirects_left=redirects_left - 1,
                )
            declared = response.headers.get("content-length", "")
            if declared.isdigit() and int(declared) > self._max_bytes:
                raise PayloadTooLarge("response is larger than the byte ceiling")
            body = bytearray()
            truncated = False
            async for chunk in response.aiter_bytes():
                body.extend(chunk)
                if len(body) >= self._max_bytes:
                    del body[self._max_bytes :]
                    truncated = True
                    break
            return HttpResponse(
                status=response.status_code,
                url=str(response.url),
                headers={key.lower(): value for key, value in response.headers.items()},
                body=bytes(body),
                truncated=truncated,
            )
        finally:
            await response.aclose()

    async def _robots_policy(
        self,
        origin: str,
        allow_private: bool,
        allowed_hosts: Sequence[str],
    ) -> RobotsPolicy | None:
        now = self._clock()
        entry = self._robots.get(origin)
        if entry is not None and entry.expires_at > now:
            if self._on_cache_hit is not None:
                self._on_cache_hit()
            return entry.policy
        try:
            response = await self._request(
                "GET",
                f"{origin}/robots.txt",
                params=None,
                content=None,
                headers=None,
                check_robots=False,
                allow_private=allow_private,
                allowed_hosts=allowed_hosts,
                timeout_s=self._timeout_s,
                redirects_left=0,
            )
        except FetchError as exc:
            # RFC 9309 §2.3.1.4：5xx / 网络错误视为不可用，按拒绝处理（fail-closed）。
            raise FetchRejected("robots.txt is unavailable") from exc
        if response.status == 401 or response.status == 403:
            policy: RobotsPolicy | None = _DENY_ALL
        elif 200 <= response.status < 300:
            text = response.body[:MAX_ROBOTS_BYTES].decode("utf-8", "replace")
            policy = RobotsPolicy.parse(text)
        elif 400 <= response.status < 500:
            policy = _ALLOW_ALL
        else:
            raise FetchRejected("robots.txt is unavailable")
        if self._robots_ttl_s > 0:
            self._robots[origin] = _RobotsEntry(now + self._robots_ttl_s, policy)
        return policy

    @staticmethod
    def _encode(content: object | None) -> bytes | None:
        if content is None:
            return None
        if isinstance(content, dict):
            return json.dumps(content, ensure_ascii=False).encode("utf-8")
        if isinstance(content, str):
            return content.encode("utf-8")
        return None
