package com.omniretail.backend.inventory.dto;

import java.util.List;

/**
 * @param duplicates seriales (normalizados) que ya existen para el producto en el tenant.
 * @param repeatedInRequest seriales (normalizados) que aparecen más de una vez en el propio request.
 */
public record ValidateSerialsResponse(List<String> duplicates, List<String> repeatedInRequest) {}
