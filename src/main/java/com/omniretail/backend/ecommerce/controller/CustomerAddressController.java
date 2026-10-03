package com.omniretail.backend.ecommerce.controller;

import com.omniretail.backend.ecommerce.dto.AddressRequest;
import com.omniretail.backend.ecommerce.dto.AddressResponse;
import com.omniretail.backend.ecommerce.service.CustomerAddressService;
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
 * Direcciones del cliente autenticado. El tenant y el cliente salen del JWT; una direccion de otro
 * cliente responde 404 igual que una inexistente.
 */
@RestController
@RequestMapping("/me/addresses")
@RequiredArgsConstructor
@RequirePermission("customer.address.manage")
@Tag(name = "Mi cuenta", description = "Perfil, direcciones y métodos de pago del cliente autenticado.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Sin token, token inválido o vencido, o sesión revocada."),
    @ApiResponse(
            responseCode = "403",
            description = "Sin el permiso (`ACCESS_DENIED`), o la sesión no es de un cliente activo "
                    + "(`CUSTOMER_ACCOUNT_REQUIRED`).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
})
public class CustomerAddressController {

    private static final String VALIDATION_DESCRIPTION =
            "Datos inválidos o campos no permitidos (`VALIDATION_ERROR`, con `fields`), o cuerpo ilegible "
                    + "(`REQUEST_ERROR`). `isDefault`, `tenantId` y `customerId` no se aceptan.";
    private static final String NOT_FOUND_DESCRIPTION =
            "La dirección no existe o no pertenece al cliente (`ADDRESS_NOT_FOUND`).";

    private final CustomerAddressService customerAddressService;

    @GetMapping
    @Operation(
            summary = "Consultar mis direcciones",
            description = "Devuelve las direcciones del cliente, de la más antigua a la más reciente.")
    @ApiResponse(
            responseCode = "200",
            description = "Direcciones del cliente.",
            content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = AddressResponse.class))))
    public List<AddressResponse> list() {
        return customerAddressService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Crear dirección",
            description = """
                    Crea una dirección de entrega. La primera del cliente nace predeterminada.

                    - `stateOrDepartment` debe ser un departamento de Guatemala y `city` un municipio de ese departamento.
                    - `postalCode` es opcional (5 dígitos).
                    - `country` se acepta, pero siempre se guarda `Guatemala`.""")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Dirección creada.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = AddressResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = VALIDATION_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public AddressResponse create(@RequestBody AddressRequest request) {
        return customerAddressService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Editar dirección",
            description = "Reemplaza los datos de la dirección con las mismas reglas que al crearla. No cambia "
                    + "cuál es la predeterminada.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Dirección actualizada.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = AddressResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = VALIDATION_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "404",
                description = NOT_FOUND_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public AddressResponse update(@PathVariable UUID id, @RequestBody AddressRequest request) {
        return customerAddressService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Eliminar dirección",
            description = "Si era la predeterminada y quedan otras, la más antigua pasa a ser la predeterminada.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Dirección eliminada."),
        @ApiResponse(
                responseCode = "404",
                description = NOT_FOUND_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public void delete(@PathVariable UUID id) {
        customerAddressService.delete(id);
    }

    @PutMapping("/{id}/default")
    @Operation(
            summary = "Marcar dirección predeterminada",
            description = "Deja esta dirección como la única predeterminada del cliente.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Dirección predeterminada actualizada.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = AddressResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = NOT_FOUND_DESCRIPTION,
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    })
    public AddressResponse setDefault(@PathVariable UUID id) {
        return customerAddressService.setDefault(id);
    }
}
