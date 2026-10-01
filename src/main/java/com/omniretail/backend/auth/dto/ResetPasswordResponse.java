package com.omniretail.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.omniretail.backend.administration.entity.UserType;

/** @param tenantSlug solo para clientes, para volver a la tienda correcta; se omite para empleados. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResetPasswordResponse(UserType userType, String tenantSlug) {
}
