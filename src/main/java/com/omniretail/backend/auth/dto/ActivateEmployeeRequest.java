package com.omniretail.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** La politica de la nueva contrasena se valida con el contexto del empleado en el service. */
public record ActivateEmployeeRequest(@NotBlank String token, @NotBlank String newPassword) {
}
