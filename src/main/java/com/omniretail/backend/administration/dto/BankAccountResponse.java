package com.omniretail.backend.administration.dto;

import com.omniretail.backend.administration.entity.BankAccount;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.entity.BankAccountType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BankAccountResponse(
        UUID id,
        UUID tenantId,
        String bankName,
        String holderName,
        String accountNumber,
        String accountNumberMasked,
        BankAccountType accountType,
        String currency,
        String alias,
        List<UUID> branchIds,
        String transferInstructions,
        BankAccountStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static BankAccountResponse from(BankAccount bankAccount) {
        return new BankAccountResponse(
                bankAccount.getId(),
                bankAccount.getTenantId(),
                bankAccount.getBankName(),
                bankAccount.getHolderName(),
                bankAccount.getAccountNumber(),
                bankAccount.getAccountNumberMasked(),
                bankAccount.getAccountType(),
                bankAccount.getCurrency(),
                bankAccount.getAlias(),
                bankAccount.getBranchIds(),
                bankAccount.getTransferInstructions(),
                bankAccount.getStatus(),
                bankAccount.getCreatedAt(),
                bankAccount.getUpdatedAt());
    }
}
