package com.omniretail.backend.ecommerce.dto;

/** Atributo activo de un producto que puede mostrarse sin autenticación. */
public record PublicStorefrontProductAttributeResponse(String name, String value) {}
