package dev.bedwars.core.shop;

import java.util.Objects;

/** A price in a single currency. */
public record Price(Currency currency, int amount) {
    public Price {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
    }

    public static Price of(Currency currency, int amount) {
        return new Price(currency, amount);
    }
}