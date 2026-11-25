package com.docqa.agentgateway.service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PiiMasker {

    public static final String VERSION = "pii-v2";
    public static final String LEGACY_VERSION = "pii-v1";

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_CARD_18 = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");
    private static final Pattern ID_CARD_15 = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[\\s-]?)?1[3-9]\\d{9}(?!\\d)");
    // 16~19 位连续数字候选；真正判定在 isBankCard（前缀 + Luhn），
    // 避免订单号/流水号这类长数字被一律当成卡号。
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    // 中国护照：E/G/S/D/P + 8 位数字（2019 版及现行）。
    private static final Pattern PASSPORT = Pattern.compile("(?<![A-Za-z0-9])[EGSDP]\\d{8}(?![A-Za-z0-9])");
    // 美式社保号 123-45-6789。
    private static final Pattern SSN = Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)");
    private static final List<String> CARD_PREFIXES =
            List.of("62", "4", "51", "52", "53", "54", "55");

    public PiiMaskingResult mask(String input) {
        return mask(input, VERSION);
    }

    /**
     * 按契约版本掩码。v1 仅三类，v2 增加银行卡/护照/社保号。
     * 与 Python {@code mask_text(text, version=...)} 同规则：梯度部署期间两侧必须用同一版本。
     */
    public PiiMaskingResult mask(String input, String version) {
        boolean v1 = LEGACY_VERSION.equals(version);
        String value = normalize(input);
        Set<String> categories = new LinkedHashSet<>();
        int total = 0;
        Replacement replacement = replace(value, EMAIL, "[EMAIL]", "EMAIL", categories);
        total += replacement.count();
        replacement = replace(replacement.value(), ID_CARD_18, "[ID_CARD]", "ID_CARD", categories);
        total += replacement.count();
        replacement = replace(replacement.value(), ID_CARD_15, "[ID_CARD]", "ID_CARD", categories);
        total += replacement.count();
        if (!v1) {
            replacement = replaceBankCards(replacement.value(), categories);
            total += replacement.count();
            replacement = replace(replacement.value(), PASSPORT, "[PASSPORT]", "PASSPORT", categories);
            total += replacement.count();
            replacement = replace(replacement.value(), SSN, "[SSN]", "SSN", categories);
            total += replacement.count();
        }
        replacement = replace(replacement.value(), PHONE, "[PHONE]", "PHONE", categories);
        total += replacement.count();
        return new PiiMaskingResult(replacement.value(), List.copyOf(categories), total);
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static boolean isBankCard(String value) {
        if (value.length() < 16 || value.length() > 19) {
            return false;
        }
        boolean prefixOk = false;
        for (String prefix : CARD_PREFIXES) {
            if (value.startsWith(prefix)) {
                prefixOk = true;
                break;
            }
        }
        return prefixOk && luhnValid(value);
    }

    private Replacement replaceBankCards(String input, Set<String> categories) {
        Matcher matcher = BANK_CARD.matcher(input);
        StringBuffer buffer = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            String digits = matcher.group();
            if (isBankCard(digits)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement("[BANK_CARD]"));
                count++;
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(digits));
            }
        }
        if (count > 0) {
            categories.add("BANK_CARD");
            matcher.appendTail(buffer);
            return new Replacement(buffer.toString(), count);
        }
        return new Replacement(input, 0);
    }

    public boolean containsPii(String input) {
        return mask(input).count() > 0;
    }

    public String normalize(String input) {
        String value = input == null ? "" : input;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return ZERO_WIDTH.matcher(normalized).replaceAll("");
    }

    private Replacement replace(Pattern pattern, String input, String placeholder, String category,
            Set<String> categories) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(placeholder));
            count++;
        }
        if (count > 0) {
            categories.add(category);
            matcher.appendTail(buffer);
            return new Replacement(buffer.toString(), count);
        }
        return new Replacement(input, 0);
    }

    private Replacement replace(String input, Pattern pattern, String placeholder, String category,
            Set<String> categories) {
        return replace(pattern, input, placeholder, category, categories);
    }

    private record Replacement(String value, int count) {
    }
}
