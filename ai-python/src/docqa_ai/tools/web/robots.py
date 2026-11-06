from __future__ import annotations

import re
from dataclasses import dataclass

# RFC 9309：最长匹配优先，等长时 Allow 胜出；空 Disallow 表示全站允许。
MAX_ROBOTS_BYTES = 512 * 1024
_MAX_RULES = 2000
_AGENT_TOKEN = "docqa"


@dataclass(frozen=True)
class _Rule:
    allow: bool
    pattern: str
    regex: re.Pattern[str]


@dataclass(frozen=True)
class _Group:
    agents: tuple[str, ...]
    rules: tuple[_Rule, ...]


def _compile(pattern: str) -> re.Pattern[str]:
    anchored = pattern.endswith("$")
    body = pattern[:-1] if anchored else pattern
    translated = "".join(".*" if char == "*" else re.escape(char) for char in body)
    return re.compile(f"^{translated}{'$' if anchored else '.*'}")


class RobotsPolicy:
    """Parsed robots.txt: only the groups relevant to our product token are evaluated."""

    def __init__(self, groups: tuple[_Group, ...] = ()) -> None:
        self._groups = groups

    @classmethod
    def parse(cls, text: str) -> RobotsPolicy:
        groups: list[_Group] = []
        agents: list[str] = []
        rules: list[_Rule] = []
        started = False
        for raw_line in text.splitlines()[: _MAX_RULES * 2]:
            line = raw_line.split("#", 1)[0].strip()
            if not line or ":" not in line:
                continue
            field, value = line.split(":", 1)
            field = field.strip().lower()
            value = value.strip()
            if field == "user-agent":
                if started and rules:
                    groups.append(_Group(tuple(agents), tuple(rules)))
                    agents, rules, started = [], [], False
                agents.append(value.lower())
            elif field in ("allow", "disallow"):
                started = True
                if len(rules) >= _MAX_RULES:
                    continue
                # 空 Disallow 是「允许全部」的显式写法，不生成规则。
                if value:
                    rules.append(_Rule(field == "allow", value, _compile(value)))
        if agents and (rules or started):
            groups.append(_Group(tuple(agents), tuple(rules)))
        return cls(tuple(groups))

    def can_fetch(self, path: str) -> bool:
        applicable = self._matching_group()
        if applicable is None or not applicable.rules:
            return True
        target = path or "/"
        best: _Rule | None = None
        for rule in applicable.rules:
            if not rule.regex.match(target):
                continue
            # RFC 9309 §2.2.2：最长匹配优先；等长时 Allow 胜出（与书写顺序无关）。
            if best is None or len(rule.pattern) > len(best.pattern):
                best = rule
            elif len(rule.pattern) == len(best.pattern) and rule.allow and not best.allow:
                best = rule
        return True if best is None else best.allow

    def _matching_group(self) -> _Group | None:
        specific: _Group | None = None
        specific_length = 0
        wildcard: _Group | None = None
        for group in self._groups:
            for agent in group.agents:
                if agent == "*":
                    wildcard = group
                elif _AGENT_TOKEN.startswith(agent) and len(agent) > specific_length:
                    specific, specific_length = group, len(agent)
        return specific or wildcard


def robots_allowed(policy: RobotsPolicy | None, path: str) -> bool:
    return True if policy is None else policy.can_fetch(path)
