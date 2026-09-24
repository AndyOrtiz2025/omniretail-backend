/**
 * Modulo auth: autenticacion, sesiones y JWT (login, logout, /me).
 *
 * <p>Responsable: Andy.
 *
 * <p>Capas: {@code controller} (REST, sin logica), {@code service} (reglas de negocio y
 * {@code @Transactional}), {@code repository} (Spring Data JPA), {@code entity} (JPA) y {@code dto}
 * (records de request/response). Otros modulos solo consumen este a traves de sus services.
 */
package com.omniretail.backend.auth;
