package com.omniretail.backend.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unitario: sin contexto de Spring ni Testcontainers. */
class GlobalExceptionHandlerTest {

    private static final String INTERNAL_DETAIL = "detalle interno uk_users_tenant_email";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");

    @Test
    void uniqueViolationReturnsConflictDuplicateResource() {
        ResponseEntity<ApiError> response = handle(violation("23505"));

        assertError(response, HttpStatus.CONFLICT, "DUPLICATE_RESOURCE");
        assertThat(response.getBody().message()).isEqualTo("Ya existe un registro con esos datos.");
    }

    @Test
    void foreignKeyViolationReturnsConflictReferenceConflict() {
        assertError(handle(violation("23503")), HttpStatus.CONFLICT, "REFERENCE_CONFLICT");
    }

    @Test
    void checkViolationReturnsBadRequest() {
        assertError(handle(violation("23514")), HttpStatus.BAD_REQUEST, "DATA_INTEGRITY_ERROR");
    }

    @Test
    void notNullViolationReturnsBadRequest() {
        assertError(handle(violation("23502")), HttpStatus.BAD_REQUEST, "DATA_INTEGRITY_ERROR");
    }

    @Test
    void withoutSqlExceptionReturnsGenericDataConflict() {
        ResponseEntity<ApiError> response = handle(new DataIntegrityViolationException(INTERNAL_DETAIL));

        assertError(response, HttpStatus.CONFLICT, "DATA_CONFLICT");
    }

    @Test
    void unknownSqlStateReturnsGenericDataConflict() {
        assertError(handle(violation("99999")), HttpStatus.CONFLICT, "DATA_CONFLICT");
    }

    @Test
    void sqlExceptionNestedTwoLevelsIsDetected() {
        SQLException sql = new SQLException(INTERNAL_DETAIL, "23505");
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("x", new RuntimeException("envoltorio", sql));

        assertError(handle(ex), HttpStatus.CONFLICT, "DUPLICATE_RESOURCE");
    }

    private ResponseEntity<ApiError> handle(DataIntegrityViolationException ex) {
        return handler.handleDataIntegrity(ex, request);
    }

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("x", new SQLException(INTERNAL_DETAIL, sqlState));
    }

    /** Ademas del status y code, verifica que no se filtre ningun detalle interno de la BD. */
    private static void assertError(ResponseEntity<ApiError> response, HttpStatus status, String code) {
        ApiError body = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(status.value());
        assertThat(body.code()).isEqualTo(code);
        assertThat(body.path()).isEqualTo("/api/v1/test");
        assertThat(body.message()).doesNotContain("uk_", "detalle interno");
    }
}
