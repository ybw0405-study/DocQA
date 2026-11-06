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

    public static final String VERSION = "pii-v1";

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

    public PiiMaskingResult mask(String input) {
        String value = normalize(input);
        Set<String> categories = new LinkedHashSet<>();
        int total = 0;
        Replacement replacement = replace(value, EMAIL, "[EMAIL]", "EMAIL", categories);
        total += replacement.count();
        replacement = replace(replacement.value(), ID_CARD_18, "[ID_CARD]", "ID_CARD", categories);
        total += replacement.count();
        replacement = replace(replacement.value(), ID_CARD_15, "[ID_CARD]", "ID_CARD", categories);
        total += replacement.count();
        replacement = replace(replacement.value(), PHONE, "[PHONE]", "PHONE", categories);
        total += replacement.count();
        return new PiiMaskingResult(replacement.value(), List.copyOf(categories), total);
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
