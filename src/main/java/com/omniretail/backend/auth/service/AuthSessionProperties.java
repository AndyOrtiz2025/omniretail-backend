package com.omniretail.backend.auth.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sesiones del modulo auth.
 *
 * @param employeeIdleTimeout tiempo sin actividad tras el cual se cierra, en el servidor, la sesion de un
 *     empleado ({@code EMPLOYEE_IDLE_TIMEOUT_MINUTES}, 60 por defecto). Debe ser positivo: se valida al
 *     arrancar para fallar temprano. No aplica a clientes.
 */
@ConfigurationProperties("app.auth")
public record AuthSessionProperties(Duration employeeIdleTimeout) {

    public AuthSessionProperties {
        if (employeeIdleTimeout == null || employeeIdleTimeout.isNegative() || employeeIdleTimeout.isZero()) {
            throw new IllegalStateException(
                    "app.auth.employee-idle-timeout (EMPLOYEE_IDLE_TIMEOUT_MINUTES) es obligatoria y debe ser positiva.");
        }
    }
}
