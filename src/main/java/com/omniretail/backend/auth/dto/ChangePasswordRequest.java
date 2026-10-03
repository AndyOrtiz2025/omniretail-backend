package com.omniretail.backend.auth.dto;

/**
 * Cambio de contrasena con sesion iniciada. {@code confirmNewPassword} es solo del formulario y no se
 * envia. Las reglas dependen del tipo de cuenta y se validan en el service.
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
