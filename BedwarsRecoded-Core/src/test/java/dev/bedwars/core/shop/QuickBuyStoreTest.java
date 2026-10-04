package dev.bedwars.core.shop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuickBuyStoreTest {

    @Test
    void toggleAddsAndRemoves() {
        QuickBuyStore store = new QuickBuyStore();
        UUID player = UUID.randomUUID();
        store.toggle(player, "wool");
        assertThat(store.items(player)).containsExactly("wool");
        store.toggle(player, "wool");
        assertThat(store.items(player)).isEmpty();
    }

    @Test
    void setAllReplacesLayout() {
        QuickBuyStore store = new QuickBuyStore();
        UUID player = UUID.randomUUID();
        store.toggle(player, "old");
        store.setAll(player, List.of("a", "b"));
        assertThat(store.items(player)).containsExactly("a", "b");
        assertThat(store.contains(player, "old")).isFalse();
    }
}