package com.docqa.document.support;

import com.docqa.document.domain.TagName;
import java.util.Locale;

public final class TagNormalizer {

    public static final int MAX_TAG_LENGTH = 24;

    private TagNormalizer() {
    }

    public static TagName of(String raw) {
        String display = foldWhitespace(raw);
        return new TagName(display, display.toLowerCase(Locale.ROOT));
    }

    private static String foldWhitespace(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder folded = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                pendingSpace = folded.length() > 0;
                continue;
            }
            if (pendingSpace) {
                folded.append(' ');
                pendingSpace = false;
            }
            folded.append(character);
        }
        return folded.toString();
    }
}
