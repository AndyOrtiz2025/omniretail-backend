package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.CreatePaymentMethodRequest;
import com.omniretail.backend.ecommerce.dto.PaymentMethodResponse;
import com.omniretail.backend.ecommerce.dto.UpdatePaymentMethodRequest;
import com.omniretail.backend.ecommerce.service.CustomerPaymentMethodService;
import com.omniretail.backend.shared.exception.ApiError;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tarjetas guardadas del cliente autenticado. El tenant y el cliente salen del JWT; una tarjeta de otro
 * cliente responde 404 igual que una inexistente. Nunca se acepta el numero completo ni el CVV.
 */
@RestController
@RequestMapping("/me/payment-methods")
@RequiredArgsConstructor
@RequirePermission("customer.payment_method.manage")
@Tag(name = "Customer payment methods", description = "Mi cuenta: tarjetas guardadas del cliente autenticado. "
        + "Nunca se guarda ni se acepta el número completo ni el CVV.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Sin token, token inválido o vencido, o sesión revocada. "
            + "Responde sin cuerpo."),
    @ApiResponse(
            responseCode = "403",
            description = "Sin el permiso (`ACCESS_DENIED`), o la sesión no es de un cliente activo "
                    + "(`CUSTOMER_ACCOUNT_REQUIRED`).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
})
public class CustomerPaymentMethodController {

    private static final String NOT_FOUND_DESCRIPTION =
            "La tarjeta no existe o no pertenece al cliente (`PAYMENT_METHOD_NOT_FOUND`).";

    private final CustomerPaymentMethodService customerPaymentMethodService;

    @GetMapping
    @Operation(
            summary = "List payment methods",
            description = "**Solo clientes autenticados** (permiso `customer.payment_method.manage`). Devuelve las tarjetas guardadas: la principal primero y luego de la más antigua a la "
                    + "más reciente. Nunca incluye el token del proveedor.")
    @ApiResponse(
            responseCode = "200",
            description = "Tarjetas del cliente.",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = PaymentMethodResponse.class))))
    public List<PaymentMethodResponse> list() {
        return customerPaymentMethodService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Add payment method",
            description = """
                    **Solo clientes autenticados.** Guarda una tarjeta. La primera del cliente nace principal.

                    - `brand` y `issuingBank` deben ser valores de las listas del frontend.
                    - `last4`: exactamente 4 dígitos. Nunca se envía el número completo ni el CVV.
                    - El vencimiento no puede estar vencido (zona America/Guatemala) ni pasar del año actual + 20.
                    - El servidor genera el token del proveedor; no se acepta ni se devuelve.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Tarjeta guardada.",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PaymentMethodResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos o campos no permitidos (`VALIDATION_ERROR`, con `fields`), o cuerpo "
                        + "ilegible (`REQUEST_ERROR`). `cardNumber`, `cvv`, `providerPaymentMethodId`, `isDefault`, "
                        + "`tenantId` y `customerId` no se aceptan.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public PaymentMethodResponse create(@RequestBody CreatePaymentMethodRequest request) {
        return customerPaymentMethodService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Update payment method",
            description = "**Solo clientes autenticados.** Reemplaza el titular y el vencimiento con las mismas reglas que al guardarla. Marca, "
                    + "banco y últimos 4 no se pueden cambiar; tampoco cambia cuál es la principal.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tarjeta actualizada.",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PaymentMethodResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Datos inválidos o campos no permitidos (`VALIDATION_ERROR`, con `fields`), o cuerpo "
                        + "ilegible (`REQUEST_ERROR`). Solo se aceptan `cardholderName`, `expirationMonth` y "
                        + "`expirationYear`.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = NOT_FOUND_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public PaymentMethodResponse update(@PathVariable UUID id, @RequestBody UpdatePaymentMethodRequest request) {
        return customerPaymentMethodService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete payment method",
            description = "**Solo clientes autenticados.** Elimina la tarjeta. Si era la principal y quedan otras, la más antigua pasa a ser la principal.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tarjeta eliminada."),
        @ApiResponse(
                responseCode = "404",
                description = NOT_FOUND_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public void delete(@PathVariable UUID id) {
        customerPaymentMethodService.delete(id);
    }

    @PutMapping("/{id}/default")
    @Operation(
            summary = "Set default payment method",
            description = "**Solo clientes autenticados.** Deja esta tarjeta como la única principal del cliente.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tarjeta principal actualizada.",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PaymentMethodResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = NOT_FOUND_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public PaymentMethodResponse setDefault(@PathVariable UUID id) {
        return customerPaymentMethodService.setDefault(id);
    }
}
