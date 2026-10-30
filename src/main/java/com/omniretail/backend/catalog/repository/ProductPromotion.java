package com.omniretail.backend.catalog.repository;

import com.omniretail.backend.catalog.entity.Promotion;
import java.util.UUID;

/** Promocion vigente asociada a un producto, para resolver precios de varios productos en lote. */
public record ProductPromotion(UUID productId, Promotion promotion) {
}
