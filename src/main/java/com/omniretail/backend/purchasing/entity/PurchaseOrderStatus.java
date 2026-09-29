package com.omniretail.backend.purchasing.entity;

public enum PurchaseOrderStatus {
    draft,
    pending_approval,
    approved,
    sent,
    partially_received,
    received,
    cancelled
}
