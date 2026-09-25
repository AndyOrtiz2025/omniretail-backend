package com.omniretail.backend.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "La solicitud tiene datos invalidos.", request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                "REQUEST_ERROR",
                "El cuerpo de la solicitud contiene datos invalidos.",
                request,
                null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "No tienes permiso para realizar esta accion.", request, null);
    }

    /**
     * Red de seguridad ante condiciones de carrera: la BD rechaza lo que las validaciones previas
     * (existsBy...) no alcanzaron a detectar. Al cliente nunca va el nombre de la constraint, tabla,
     * columna ni el mensaje SQL; eso solo queda en el log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String sqlState = findCause(ex, SQLException.class).map(SQLException::getSQLState).orElse(null);
        String constraint = findCause(ex, ConstraintViolationException.class)
                .map(ConstraintViolationException::getConstraintName)
                .orElse(null);
        log.warn("Violacion de integridad en {} {}: sqlState={}, constraint={}",
                request.getMethod(), request.getRequestURI(), sqlState, constraint);

        if ("23505".equals(sqlState)) {
            return build(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "Ya existe un registro con esos datos.", request, null);
        }
        if ("23503".equals(sqlState)) {
            return build(HttpStatus.CONFLICT, "REFERENCE_CONFLICT",
                    "La operación no es posible porque el registro está relacionado con otros datos.", request, null);
        }
        if ("23514".equals(sqlState) || "23502".equals(sqlState)) {
            return build(HttpStatus.BAD_REQUEST, "DATA_INTEGRITY_ERROR",
                    "Los datos no cumplen las reglas requeridas.", request, null);
        }
        return build(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "No se pudo completar la operación por un conflicto de datos.", request, null);
    }

    /** Ultimo recurso: se registra el detalle en el log, pero al cliente solo va un mensaje generico. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Excepciones estandar de Spring MVC (404, 405, 415, cuerpo ilegible...) conservan su codigo HTTP.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return build(status, "REQUEST_ERROR", status.getReasonPhrase(), request, null);
        }
        log.error("Error no controlado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ocurrio un error inesperado.", request, null);
    }

    /** Recorre la cadena de causas (sin incluir {@code ex}) hasta encontrar una del tipo pedido. */
    private static <T extends Throwable> Optional<T> findCause(Throwable ex, Class<T> type) {
        Throwable cause = ex.getCause();
        // El limite evita un bucle infinito si alguna libreria arma una cadena de causas circular.
        for (int depth = 0; cause != null && depth < 20; depth++) {
            if (type.isInstance(cause)) {
                return Optional.of(type.cast(cause));
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status, String code, String message, HttpServletRequest request, Map<String, String> fields) {
        ApiError body = new ApiError(
                status.value(), status.getReasonPhrase(), code, message, request.getRequestURI(), fields, Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}
