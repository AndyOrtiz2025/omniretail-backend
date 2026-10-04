package com.omniretail.backend.auth.service;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj del login, el bloqueo por intentos y el MFA. Se inyecta por nombre ({@code authClock}) para que los
 * tests puedan adelantarlo y probar ventanas y niveles de bloqueo sin esperar.
 */
@Configuration
public class AuthClockConfiguration {

    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }
}
