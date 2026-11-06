from __future__ import annotations

import pytest

from docqa_ai.tools.web.robots import RobotsPolicy, robots_allowed


def policy(text: str) -> RobotsPolicy:
    return RobotsPolicy.parse(text)


@pytest.mark.parametrize(
    ("text", "path", "expected"),
    [
        ("User-agent: *\nDisallow:\n", "/anything", True),
        ("User-agent: *\nDisallow: /private\n", "/private/x", False),
        ("User-agent: *\nDisallow: /private\n", "/public", True),
        ("User-agent: *\nDisallow: /*.pdf$\n", "/paper.pdf", False),
        ("User-agent: *\nDisallow: /*.pdf$\n", "/paper.pdf?x=1", True),
        ("User-agent: *\nDisallow: /a*c\n", "/abbbc", False),
        ("User-agent: *\nDisallow: /a*c\n", "/abbbd", True),
        # 最长匹配优先：更长的 Allow 覆盖更短的 Disallow。
        ("User-agent: *\nDisallow: /docs\nAllow: /docs/public\n", "/docs/public/a", True),
        ("User-agent: *\nDisallow: /docs\nAllow: /docs/public\n", "/docs/private", False),
        # 等长时 Allow 胜出，且与书写顺序无关（RFC 9309 §2.2.2）。
        ("User-agent: *\nDisallow: /page\nAllow: /page\n", "/page", True),
        ("User-agent: *\nAllow: /page\nDisallow: /page\n", "/page", True),
    ],
)
def test_rule_matching(text: str, path: str, expected: bool) -> None:
    assert policy(text).can_fetch(path) is expected


def test_specific_agent_group_wins_over_wildcard() -> None:
    text = "User-agent: *\nDisallow: /\n\nUser-agent: docqa\nDisallow: /blocked\n"
    parsed = policy(text)
    assert parsed.can_fetch("/blocked/x") is False
    assert parsed.can_fetch("/open") is True


def test_unknown_agent_falls_back_to_wildcard() -> None:
    text = "User-agent: otherbot\nDisallow: /\n\nUser-agent: *\nDisallow: /secret\n"
    parsed = policy(text)
    assert parsed.can_fetch("/secret") is False
    assert parsed.can_fetch("/ok") is True


def test_multiple_agents_in_one_group_share_rules() -> None:
    text = "User-agent: a\nUser-agent: docqa\nDisallow: /x\n"
    parsed = policy(text)
    assert parsed.can_fetch("/x") is False


def test_comments_blank_lines_and_unknown_fields_are_ignored() -> None:
    text = "# header\nSitemap: https://example.com/sitemap.xml\n\nUser-agent: * # all\nDisallow: /tmp\n"
    parsed = policy(text)
    assert parsed.can_fetch("/tmp/a") is False
    assert parsed.can_fetch("/other") is True


def test_empty_policy_allows_everything() -> None:
    assert robots_allowed(None, "/anything") is True
    assert robots_allowed(RobotsPolicy(), "/anything") is True


def test_query_string_takes_part_in_matching() -> None:
    parsed = policy("User-agent: *\nDisallow: /*?download=\n")
    assert parsed.can_fetch("/file?download=1") is False
    assert parsed.can_fetch("/file") is True


def test_rule_count_is_bounded() -> None:
    lines = ["User-agent: *"]
    lines.extend(f"Disallow: /p{i}" for i in range(2500))
    parsed = policy("\n".join(lines))
    assert parsed.can_fetch("/p0") is False
