package com.docqa.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetCursorTest {

    private static final UUID ID = UUID.fromString("0192f3a4-9d4e-7f32-8a01-1234567890ab");
    private static final Instant SORT_KEY = Instant.parse("2026-10-20T10:17:12.123Z");

    @Test
    void roundTripsSortKeyAndId() {
        String token = KeysetCursor.of(SORT_KEY, ID).encode();

        KeysetCursor decoded = KeysetCursor.decode(token).orElseThrow();

        assertThat(decoded.sortKey()).isEqualTo(SORT_KEY);
        assertThat(decoded.id()).isEqualTo(ID);
        assertThat(decoded.hasSortKey()).isTrue();
    }

    @Test
    void roundTripsNullSortKeyForTheNoTriggerGroup() {
        String token = KeysetCursor.of(null, ID).encode();

        KeysetCursor decoded = KeysetCursor.decode(token).orElseThrow();

        assertThat(decoded.sortKey()).isNull();
        assertThat(decoded.hasSortKey()).isFalse();
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    void encodesToUrlSafeTokenWithoutPadding() {
        String token = KeysetCursor.of(SORT_KEY, ID).encode();

        assertThat(token).doesNotContain("=", "+", "/");
        assertThat(token.length()).isLessThanOrEqualTo(120);
    }

    @Test
    void rejectsBlankGarbageAndForeignTokens() {
        assertThat(KeysetCursor.decode(null)).isEmpty();
        assertThat(KeysetCursor.decode("")).isEmpty();
        assertThat(KeysetCursor.decode(" ")).isEmpty();
        assertThat(KeysetCursor.decode("not-a-cursor")).isEmpty();
        assertThat(KeysetCursor.decode(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("no-separator".getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEmpty();
        assertThat(KeysetCursor.decode(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("1767222000123|not-a-uuid").getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEmpty();
        assertThat(KeysetCursor.decode(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("abc|" + ID).getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEmpty();
    }

    @Test
    void rejectsOverlongToken() {
        String token = "A".repeat(300);

        assertThat(KeysetCursor.decode(token)).isEmpty();
    }

    @Test
    void requiresId() {
        assertThatThrownBy(() -> KeysetCursor.of(SORT_KEY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cursor id is required");
    }
}
