package com.omniretail.backend.auth.dto;

/**
 * Resultado de {@code POST /auth/login} (LoginResult de AuthRepository.ts): la sesion, o un desafio de
 * segundo factor si el usuario tiene MFA activo.
 */
public sealed interface LoginOutcome permits LoginResponse, MfaChallengeResponse {
}
