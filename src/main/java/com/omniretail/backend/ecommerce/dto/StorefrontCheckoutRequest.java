package com.omniretail.backend.ecommerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Entrada pública del checkout. Solo recibe datos de contacto y cantidades: tenant, precios,
 * sucursal, estado y totales se resuelven en el servicio autenticado.
 */
public record StorefrontCheckoutRequest(
        @NotEmpty List<@Valid StorefrontCheckoutItemRequest> items,
        @NotBlank
                @Size(max = 70)
                @Pattern(regexp = "^[\\p{L}\\p{M} '-]+$", message = "Nombre de entrega inválido")
                String fullName,
        @NotBlank
                @Email
                @Size(max = 254)
                @Pattern(
                        regexp = "^(?=.{1,254}$)(?=.{1,64}@)[A-Za-z0-9](?:[A-Za-z0-9_-]|\\.(?!\\.))*[A-Za-z0-9_-]?@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$",
                        message = "Correo electrónico inválido")
                String email,
        @NotBlank
                @Pattern(regexp = "^[0-9]{8}$", message = "Teléfono inválido")
                String phone,
        @NotBlank
                @Size(max = 50)
                @Pattern(
                        regexp = "^(?!.*--)[A-Za-z0-9][A-Za-z0-9 .-]*$",
                        message = "Dirección inválida")
                String addressLine1,
        @Size(max = 50)
                @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9 ]*$", message = "Complemento inválido")
                String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 100) String department,
        @Size(max = 120)
                @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9 ,]*$", message = "Referencias inválidas")
                String references,
        @NotBlank @Size(max = 120) String cardholderName,
        @NotBlank @Pattern(regexp = "^[0-9]{4}$", message = "Últimos cuatro inválidos") String cardLastFour) {}
