package com.bhukkad.util;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

public final class CursorUtils {

    private static final String SEPARATOR = "|";

    private CursorUtils() {}

    public record OrderCursor(LocalDateTime createdAt, Long id) {}

    public static String encode(LocalDateTime createdAt, Long id) {
        if (createdAt == null || id == null) {
            throw new IllegalArgumentException("Cursor requires createdAt and id");
        }
        String raw = createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<OrderCursor> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separatorIndex = decoded.lastIndexOf(SEPARATOR);
            if (separatorIndex <= 0) {
                return Optional.empty();
            }
            LocalDateTime createdAt = LocalDateTime.parse(decoded.substring(0, separatorIndex));
            Long id = Long.parseLong(decoded.substring(separatorIndex + 1));
            return Optional.of(new OrderCursor(createdAt, id));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
