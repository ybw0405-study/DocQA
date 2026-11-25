from __future__ import annotations

import pytest

from docqa_ai.pii.masker import (
    LEGACY_VERSION,
    VERSION,
    contains_pii,
    detect_categories,
    mask_text,
    normalize,
)


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
    assert VERSION == "pii-v2"
    assert LEGACY_VERSION == "pii-v1"


def test_v2_masks_bank_card_with_luhn() -> None:
    # 6222021234567894 是 Luhn 合法的银联号（示例测试号）。
    result = mask_text("卡号 6222021234567894 请核对")
    assert result.count == 1
    assert result.categories == ("BANK_CARD",)
    assert "6222021234567894" not in result.masked
    assert "[BANK_CARD]" in result.masked


def test_v2_does_not_mask_long_number_without_card_prefix() -> None:
    # 订单号/流水号即便长度相同也不能被当成卡号：前缀不匹配。
    result = mask_text("订单号 1380013800012345")
    assert result.count == 0
    assert "1380013800012345" in result.masked


def test_v2_does_not_mask_luhn_invalid_card_prefix() -> None:
    # 62 开头但 Luhn 校验失败 → 不是卡号，不遮。
    result = mask_text("编号 6222021234567890")
    assert result.count == 0


def test_v2_masks_passport_and_ssn() -> None:
    result = mask_text("护照 E12345678，SSN 123-45-6789")
    assert result.categories == ("PASSPORT", "SSN")
    assert "E12345678" not in result.masked
    assert "123-45-6789" not in result.masked


def test_v1_does_not_apply_v2_rules() -> None:
    # 梯度部署：v1 客户端只承诺三类，Python 复检必须用声明版本，
    # 否则新规则会把 Java v1 未遮的卡号误判成漏脱敏。
    text = "卡号 6222021234567894 护照 E12345678"
    assert mask_text(text, version=LEGACY_VERSION).count == 0
    assert mask_text(text, version=VERSION).count == 2


def test_v2_bank_card_idempotent() -> None:
    first = mask_text("卡 6222021234567894")
    assert mask_text(first.masked).masked == first.masked


@pytest.mark.parametrize("value", [None, "", " ", "正常文本"])
def test_empty_and_plain_text(value: str | None) -> None:
    result = mask_text(value)
    assert result.count == 0
    assert result.categories == ()
