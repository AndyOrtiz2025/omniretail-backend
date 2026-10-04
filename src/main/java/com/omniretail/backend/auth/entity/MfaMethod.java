package com.omniretail.backend.auth.entity;

/**
 * Metodos de segundo factor (MfaMethod de MfaEnrollment.ts). El frontend tambien define {@code email};
 * se agregara aqui cuando el backend lo soporte. Mientras tanto se rechaza con 400.
 */
public enum MfaMethod {
    totp
}
