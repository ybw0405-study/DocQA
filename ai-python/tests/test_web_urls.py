from __future__ import annotations

import pytest

from docqa_ai.tools.web.urls import UrlRejected, host_matches, is_forbidden_address, validate_url


async def resolver_public(host: str, port: int) -> tuple[str, ...]:
    return ("93.184.216.34",)


async def resolver_private(host: str, port: int) -> tuple[str, ...]:
    return ("10.1.2.3",)


async def resolver_mixed(host: str, port: int) -> tuple[str, ...]:
    return ("93.184.216.34", "192.168.1.10")


async def resolver_empty(host: str, port: int) -> tuple[str, ...]:
    return ()


async def resolver_failure(host: str, port: int) -> tuple[str, ...]:
    raise OSError("dns down")


async def test_accepts_public_https_and_strips_fragment() -> None:
    target = await validate_url("https://example.com/a?b=1#frag", resolver=resolver_public)
    assert target.url == "https://example.com/a?b=1"
    assert target.host == "example.com"
    assert target.origin == "https://example.com"


async def test_origin_keeps_non_default_port() -> None:
    target = await validate_url("http://example.com:8080/x", resolver=resolver_public)
    assert target.origin == "http://example.com:8080"


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "ftp://example.com/x",
        "javascript:alert(1)",
        "data:text/html,<b>x</b>",
        "https://user:pass@example.com/",
        "https:///nohost",
        "",
        "https://example.com/" + "a" * 2048,
    ],
)
async def test_rejects_structural_problems(url: str) -> None:
    with pytest.raises(UrlRejected):
        await validate_url(url, resolver=resolver_public)


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1/",
        "http://10.0.0.1/",
        "http://192.168.1.1/",
        "http://169.254.169.254/latest/meta-data/",
        "http://[::1]/",
        "http://0.0.0.0/",
    ],
)
async def test_rejects_private_ip_literals(url: str) -> None:
    with pytest.raises(UrlRejected, match="private or reserved"):
        await validate_url(url, resolver=resolver_public)


@pytest.mark.parametrize("host", ["localhost", "printer.local", "svc.internal", "x.home.arpa"])
async def test_rejects_non_routable_host_suffixes(host: str) -> None:
    with pytest.raises(UrlRejected, match="not routable"):
        await validate_url(f"http://{host}/", resolver=resolver_public)


async def test_rejects_dns_that_resolves_to_private_space() -> None:
    with pytest.raises(UrlRejected, match="private or reserved"):
        await validate_url("https://attacker.example/", resolver=resolver_private)
    with pytest.raises(UrlRejected, match="private or reserved"):
        await validate_url("https://attacker.example/", resolver=resolver_mixed)


async def test_rejects_unresolvable_and_empty_dns() -> None:
    with pytest.raises(UrlRejected, match="does not resolve"):
        await validate_url("https://missing.example/", resolver=resolver_failure)
    with pytest.raises(UrlRejected, match="does not resolve"):
        await validate_url("https://missing.example/", resolver=resolver_empty)


async def test_allowlist_matches_exact_host_and_subdomains_only() -> None:
    target = await validate_url(
        "https://docs.example.com/a",
        allowed_hosts=("example.com",),
        resolver=resolver_public,
    )
    assert target.host == "docs.example.com"
    with pytest.raises(UrlRejected, match="allowlist"):
        await validate_url(
            "https://notexample.com/a",
            allowed_hosts=("example.com",),
            resolver=resolver_public,
        )


async def test_allow_private_is_opt_in_for_operator_endpoints() -> None:
    target = await validate_url("http://127.0.0.1:8888/search", allow_private=True)
    assert target.host == "127.0.0.1"


async def test_an_explicit_none_resolver_falls_back_to_the_system_resolver(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """S7b-6-H1：客户端默认把 None 传下来，不能再让它变成「跳过 DNS 校验」。"""

    calls: list[tuple[str, int]] = []

    async def fake_resolver(host: str, port: int) -> tuple[str, ...]:
        calls.append((host, port))
        return ("93.184.216.34",)

    monkeypatch.setattr("docqa_ai.tools.web.urls._system_resolver", fake_resolver)
    target = await validate_url("https://example.com/", resolver=None)
    assert calls == [("example.com", 443)]
    assert target.host == "example.com"


async def test_host_matches_helper() -> None:
    assert host_matches("a.example.com", ("example.com",))
    assert not host_matches("badexample.com", ("example.com",))
    assert host_matches("anything.test", ())


@pytest.mark.parametrize(
    "address",
    [
        "127.0.0.1",
        "10.0.0.7",
        "169.254.169.254",
        "::1",
        "::ffff:10.0.0.7",
        "fe80::1%eth0",
        "not-an-ip",
    ],
)
def test_forbidden_addresses_cover_private_and_mapped_forms(address: str) -> None:
    assert is_forbidden_address(address)


@pytest.mark.parametrize("address", ["93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"])
def test_public_addresses_are_not_forbidden(address: str) -> None:
    assert not is_forbidden_address(address)
