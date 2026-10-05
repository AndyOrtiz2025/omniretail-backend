package com.omniretail.backend.auth.entity;

/** Metodos de segundo factor (MfaMethod de MfaEnrollment.ts): app autenticadora (TOTP) o codigo por correo. */
public enum MfaMethod {
    totp,
    email
}
