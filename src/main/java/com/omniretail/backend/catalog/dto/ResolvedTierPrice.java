package com.omniretail.backend.catalog.dto;

/** Precio vigente desde una cantidad mínima: precio por cantidad con la promoción aplicable. */
public record ResolvedTierPrice(int minQuantity, ResolvedProductPrice price) {}
