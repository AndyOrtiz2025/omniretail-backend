package com.omniretail.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** La politica de {@code newPassword} depende del tipo de cuenta y se valida en el service. */
public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, String newPassword) {
}
