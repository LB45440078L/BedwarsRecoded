package dev.bedwars.core.shop;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a purchase.
 *
 * @param refunded the lower-tier item whose cost should be refunded/removed when a
 *                 downgradable item is upgraded, if any
 */
public record PurchaseResult(PurchaseStatus status, Optional<ShopItem> refunded) {

    public PurchaseResult {
        Objects.requireNonNull(status, "status");
        if (refunded == null) {
            refunded = Optional.empty();
        }
    }

    public static PurchaseResult success() {
        return new PurchaseResult(PurchaseStatus.SUCCESS, Optional.empty());
    }

    public static PurchaseResult success(ShopItem refunded) {
        return new PurchaseResult(PurchaseStatus.SUCCESS, Optional.of(refunded));
    }

    public static PurchaseResult of(PurchaseStatus status) {
        return new PurchaseResult(status, Optional.empty());
    }

    public boolean successful() {
        return status == PurchaseStatus.SUCCESS;
    }
}