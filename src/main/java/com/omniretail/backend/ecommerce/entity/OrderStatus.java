package com.omniretail.backend.ecommerce.entity;

public enum OrderStatus {
    pending,
    confirmed,
    preparing,
    picking,
    packing,
    ready_for_pickup,
    ready_for_dispatch,
    dispatched,
    delivered,
    cancelled
}
