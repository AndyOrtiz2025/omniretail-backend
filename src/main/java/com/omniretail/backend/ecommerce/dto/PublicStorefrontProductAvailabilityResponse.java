package com.omniretail.backend.ecommerce.dto;

import java.util.UUID;

/** Disponibilidad booleana de un producto en una sucursal pública activa. */
public record PublicStorefrontProductAvailabilityResponse(
        UUID branchId, String branchName, String address, boolean available) {}
