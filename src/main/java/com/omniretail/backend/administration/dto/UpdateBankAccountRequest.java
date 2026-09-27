package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.entity.BankAccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record UpdateBankAccountRequest(
        @NotBlank @Size(max = 80) String bankName,
        @NotBlank @Size(max = 120) String holderName,
        @Pattern(
                        regexp = "^$|^[0-9\\s-]+$",
                        message = "El número de cuenta solo puede contener dígitos, espacios o guiones.")
                @Size(max = 24)
                String accountNumber,
        @NotNull BankAccountType accountType,
        @NotBlank @Pattern(regexp = "^(GTQ|USD)$", message = "Moneda no válida (debe ser GTQ o USD)") String currency,
        @NotBlank @Size(max = 50) String alias,
        List<UUID> branchIds,
        @Size(max = 300) String transferInstructions,
        @NotNull BankAccountStatus status) {
}
