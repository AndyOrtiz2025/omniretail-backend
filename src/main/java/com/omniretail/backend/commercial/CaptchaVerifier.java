package com.omniretail.backend.commercial;

/** Verificacion anti-robots del formulario comercial. Ante cualquier duda falla cerrado (devuelve false). */
public interface CaptchaVerifier {

    boolean verify(String token, String clientIp);
}
