package com.omniretail.backend.commercial;

import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.security.ClientAddressResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solicitudes comerciales de la landing MARJYM. Publico (entra por {@code /api/v1/public/**}, ver SecurityConfig)
 * y solo existe con {@code app.commercial.contact-requests.enabled=true}. El contrato no depende de que la landing
 * y el servicio compartan servidor: puede moverse a un servicio comercial independiente sin cambiar el formulario.
 */
@RestController
@RequestMapping("/public/contact-requests")
@RequiredArgsConstructor
@ContactRequestsEnabled
@Tag(name = "Commercial contact requests", description = "Solicitudes comerciales de la landing (servicio comercial).")
public class ContactRequestController {

    private final ContactRequestService service;

    @PostMapping
    @Operation(
            summary = "Register a commercial contact request",
            description = """
                    Anónimo. Registra la solicitud de forma duradera y responde 202 con su `requestId`; los correos \
                    (confirmación al solicitante y aviso al equipo) salen después, con reintentos. No crea usuarios, \
                    tenants, suscripciones ni pagos. `Idempotency-Key` (UUID, opcional): la misma clave, o el mismo \
                    correo y negocio el mismo día, devuelven el mismo `requestId` sin duplicar correos.""")
    @ApiResponse(
            responseCode = "202",
            description = "Solicitud registrada.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ContactRequestAccepted.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Datos inválidos (`VALIDATION_ERROR`).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Demasiadas solicitudes (`RATE_LIMITED` o `CONTACT_QUOTA_EXCEEDED`).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "No se pudo registrar (`CONTACT_REQUESTS_UNAVAILABLE`); no se guardó nada.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ContactRequestAccepted> create(
            @Valid @RequestBody CreateContactRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        ContactRequestAccepted accepted =
                service.submit(body, parseKey(idempotencyKey), ClientAddressResolver.resolve(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    private static UUID parseKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw FieldValidationException.of("Idempotency-Key", "Debe ser un UUID.");
        }
    }
}
