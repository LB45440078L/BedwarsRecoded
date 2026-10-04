package dev.bedwars.core.shop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A shop: an ordered set of categories. */
public record Shop(String id, String displayName, List<ShopCategory> categories) {

    public Shop {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        categories = List.copyOf(categories);
    }

    public Optional<ShopCategory> category(String categoryId) {
        return categories.stream().filter(category -> category.id().equals(categoryId)).findFirst();
    }

    public Optional<ShopItem> item(String itemId) {
        return categories.stream().map(category -> category.item(itemId))
                .flatMap(Optional::stream).findFirst();
    }
}