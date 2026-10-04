package com.omniretail.backend.auth.dto;

/**
 * Cambio de contrasena con sesion iniciada. {@code confirmNewPassword} es solo del formulario y no se
 * envia. Las reglas dependen del tipo de cuenta y se validan en el service.
 *
 * @param mfaCode codigo de la app o de recuperacion; obligatorio solo si el usuario tiene MFA activo
 *     (ChangePasswordInput.mfaCodeMock).
 */
public record ChangePasswordRequest(String currentPassword, String newPassword, String mfaCode) {
}
