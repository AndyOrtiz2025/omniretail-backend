package com.omniretail.backend.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Capa 3 del modelo de seguridad: exige que el rol del usuario autenticado tenga el permiso
 * {@link #value()}. El permiso se lee del rol en la BD en cada llamada; no viaja en el JWT.
 *
 * <p>Si falta el permiso responde 403 ({@code ACCESS_DENIED}). En una clase aplica a todos sus
 * metodos; una anotacion en el metodo reemplaza la de la clase.
 *
 * <pre>{@code
 * @RequirePermission("admin.branches.manage")
 * @PostMapping
 * public BranchResponse create(...) { ... }
 * }</pre>
 *
 * <p>Se resuelve con la plantilla {@code {value}} de {@link SecurityConfig#annotationTemplateDefaults()}.
 * El valor debe ser una constante del codigo (p. ej. {@code modulo.recurso.accion}), nunca dato del usuario.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@permissionChecker.hasPermission('{value}')")
public @interface RequirePermission {

    String value();
}
