package com.omniretail.backend.commercial;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Marca todo componente del servicio de solicitudes comerciales (controller, servicio, listeners, jobs y filtro).
 * Con {@code app.commercial.contact-requests.enabled} ausente o en false NINGUNO existe: no hay ruta, ni jobs, ni
 * correos comerciales. Las instalaciones de clientes lo dejan apagado.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnProperty(prefix = "app.commercial.contact-requests", name = "enabled", havingValue = "true")
public @interface ContactRequestsEnabled {
}
