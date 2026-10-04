package dev.bedwars.core.shop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A shop tab: an icon slot in the shop menu and the items it contains. */
public record ShopCategory(String id, String displayName, int slot, String iconMaterial, List<ShopItem> items) {

    public ShopCategory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(iconMaterial, "iconMaterial");
        items = List.copyOf(items);
    }

    public Optional<ShopItem> item(String itemId) {
        return items.stream().filter(item -> item.id().equals(itemId)).findFirst();
    }
}