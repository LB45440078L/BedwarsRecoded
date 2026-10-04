package dev.bedwars.core.i18n;

import java.util.Map;
import java.util.Objects;

/** A complete translation set for one locale. */
public record Language(String code, String displayName, Map<MessageKey, String> messages) {

    public Language {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(displayName, "displayName");
        messages = Map.copyOf(messages);
    }

    public String message(MessageKey key) {
        return messages.getOrDefault(key, key.name());
    }
}