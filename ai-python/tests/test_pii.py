from __future__ import annotations

import pytest

from docqa_ai.pii.masker import VERSION, contains_pii, detect_categories, mask_text, normalize


def test_mask_email_phone_and_id_cards() -> None:
    text = "邮箱 Test.User+tag@Example.COM，手机 13800138000，身份证 11010519491231002X 和 110105880101123。"
    result = mask_text(text)
    assert result.count == 4
    assert result.categories == ("EMAIL", "ID_CARD", "PHONE")
    assert "[EMAIL]" in result.masked
    assert "[PHONE]" in result.masked
    assert result.masked.count("[ID_CARD]") == 2
    assert "13800138000" not in result.masked
    assert "19491231002X" not in result.masked
    assert "880101123" not in result.masked
    assert "Test.User" not in result.masked


def test_mask_is_idempotent() -> None:
    first = mask_text("联系 a@b.com 或 13800138000")
    second = mask_text(first.masked)
    assert second.masked == first.masked
    assert second.count == 0


def test_full_width_and_zero_width_are_normalized() -> None:
    text = "邮箱：ａｂｃ＠ｅｘａｍｐｌｅ．ｃｏｍ，手机：１３８\u200b００１３８０００"
    result = mask_text(text)
    assert result.count == 2
    assert set(result.categories) == {"EMAIL", "PHONE"}
    assert "ａｂｃ" not in result.masked


def test_normalize_strips_zero_width_and_applies_nfkc() -> None:
    assert normalize("Ａ\u200bＢ") == "AB"


def test_contains_and_detect_helpers() -> None:
    assert contains_pii("call 13800138000")
    assert not contains_pii("no sensitive data here")
    categories, count = detect_categories("a@b.com and 13800138000")
    assert count == 2
    assert categories == ("EMAIL", "PHONE")


def test_phone_inside_longer_number_is_not_masked() -> None:
    result = mask_text("订单号 1380013800012345")
    assert result.count == 0


def test_invalid_id_card_dates_are_not_masked() -> None:
    assert not contains_pii("110105991332123")


def test_masking_version_is_stable() -> None:
    assert VERSION == "pii-v1"


@pytest.mark.parametrize("value", [None, "", "   ", "正常文本"])
def test_empty_and_plain_text(value: str | None) -> None:
    result = mask_text(value)
    assert result.count == 0
    assert result.categories == ()
