package dev.bedwars.core.shop;

/** Outcome of a shop purchase attempt. */
public enum PurchaseStatus {
    SUCCESS,
    INSUFFICIENT_FUNDS,
    ALREADY_OWNED,
    UNKNOWN_ITEM
}