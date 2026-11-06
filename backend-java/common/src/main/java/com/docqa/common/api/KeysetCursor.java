package com.docqa.common.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public final class KeysetCursor {

    private static final String SEPARATOR = "|";
    private static final int MAX_TOKEN_LENGTH = 200;

    private final Instant sortKey;
    private final UUID id;

    private KeysetCursor(Instant sortKey, UUID id) {
        this.sortKey = sortKey;
        this.id = id;
    }

    public static KeysetCursor of(Instant sortKey, UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("cursor id is required");
        }
        return new KeysetCursor(sortKey, id);
    }

    public Instant sortKey() {
        return sortKey;
    }

    public UUID id() {
        return id;
    }

    public boolean hasSortKey() {
        return sortKey != null;
    }

    public String encode() {
        String millis = sortKey == null ? "" : Long.toString(sortKey.toEpochMilli());
        String raw = millis + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<KeysetCursor> decode(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                return Optional.empty();
            }
            String head = raw.substring(0, separator);
            UUID id = UUID.fromString(raw.substring(separator + 1));
            Instant sortKey = head.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(head));
            return Optional.of(new KeysetCursor(sortKey, id));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
