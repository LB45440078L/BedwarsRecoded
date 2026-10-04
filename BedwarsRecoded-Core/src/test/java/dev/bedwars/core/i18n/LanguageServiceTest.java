package dev.bedwars.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageServiceTest {

    @Test
    void defaultsToEnglishAndFormats() {
        LanguageService service = new LanguageService(new MessageCatalog());
        UUID player = UUID.randomUUID();
        assertThat(service.languageOf(player)).isEqualTo("en");
        assertThat(service.render(player, MessageKey.GAME_COUNTDOWN, 5))
                .isEqualTo("Game starting in 5 seconds!");
    }

    @Test
    void rejectsUnknownLanguage() {
        LanguageService service = new LanguageService(new MessageCatalog());
        assertThat(service.setLanguage(UUID.randomUUID(), "xx")).isFalse();
    }

    @Test
    void registeredLanguageIsUsed() {
        MessageCatalog catalog = new MessageCatalog();
        Map<MessageKey, String> fr = new EnumMap<>(MessageKey.class);
        fr.put(MessageKey.GAME_COUNTDOWN, "Partie dans {0} secondes !");
        catalog.register(new Language("fr", "Français", fr));

        LanguageService service = new LanguageService(catalog);
        UUID player = UUID.randomUUID();
        assertThat(service.setLanguage(player, "fr")).isTrue();
        assertThat(service.render(player, MessageKey.GAME_COUNTDOWN, 3))
                .isEqualTo("Partie dans 3 secondes !");
        // Missing key falls back to English.
        assertThat(service.render(player, MessageKey.SUDDEN_DEATH))
                .isEqualTo("Sudden death! All beds have been destroyed.");
    }
}