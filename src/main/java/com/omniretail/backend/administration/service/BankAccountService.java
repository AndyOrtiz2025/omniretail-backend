package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.BankAccountResponse;
import com.omniretail.backend.administration.dto.CreateBankAccountRequest;
import com.omniretail.backend.administration.dto.UpdateBankAccountRequest;
import com.omniretail.backend.administration.entity.BankAccount;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final BranchRepository branchRepository;
    private final CurrentUser currentUser;

    public PageResponse<BankAccountResponse> listBankAccounts(BankAccountStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<BankAccount> page = status != null
                ? bankAccountRepository.findByTenantIdAndStatus(tenantId, status, pageable)
                : bankAccountRepository.findByTenantId(tenantId, pageable);
        return PageResponse.from(page, BankAccountResponse::from);
    }

    public List<BankAccountResponse> listActiveBankAccounts() {
        UUID tenantId = currentUser.require().tenantId();
        return bankAccountRepository.findByTenantIdAndStatus(tenantId, BankAccountStatus.active).stream()
                .map(BankAccountResponse::from)
                .toList();
    }

    public BankAccountResponse getBankAccountById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        BankAccount bankAccount = bankAccountRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Cuenta bancaria no encontrada."));
        return BankAccountResponse.from(bankAccount);
    }

    public BankAccountResponse createBankAccount(CreateBankAccountRequest request) {
        UUID tenantId = currentUser.require().tenantId();

        String alias = request.alias().trim();
        if (bankAccountRepository.existsByTenantIdAndAliasIgnoreCase(tenantId, alias)) {
            throw BusinessException.conflict(
                    "BANK_ACCOUNT_ALIAS_EXISTS", "Ya existe una cuenta bancaria con el alias " + alias);
        }

        String accountNumber = normalizeAccountNumber(request.accountNumber());
        ensureBranchesBelongToTenant(tenantId, request.branchIds());

        BankAccount bankAccount = BankAccount.builder()
                .bankName(request.bankName().trim())
                .holderName(request.holderName().trim())
                .accountNumber(accountNumber)
                .accountNumberMasked(mask(accountNumber))
                .accountType(request.accountType())
                .currency(request.currency())
                .alias(alias)
                .branchIds(request.branchIds())
                .transferInstructions(normalize(request.transferInstructions()))
                .status(request.status() != null ? request.status() : BankAccountStatus.active)
                .build();
        bankAccount.setTenantId(tenantId);
        BankAccount saved = bankAccountRepository.save(bankAccount);
        return BankAccountResponse.from(saved);
    }

    public BankAccountResponse updateBankAccount(UUID id, UpdateBankAccountRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        BankAccount bankAccount = bankAccountRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Cuenta bancaria no encontrada."));

        String alias = request.alias().trim();
        if (!alias.equalsIgnoreCase(bankAccount.getAlias())
                && bankAccountRepository.existsByTenantIdAndAliasIgnoreCaseAndIdNot(tenantId, alias, id)) {
            throw BusinessException.conflict(
                    "BANK_ACCOUNT_ALIAS_EXISTS", "Ya existe una cuenta bancaria con el alias " + alias);
        }

        ensureBranchesBelongToTenant(tenantId, request.branchIds());

        if (request.accountNumber() != null && !request.accountNumber().isBlank()) {
            String accountNumber = normalizeAccountNumber(request.accountNumber());
            bankAccount.setAccountNumber(accountNumber);
            bankAccount.setAccountNumberMasked(mask(accountNumber));
        }

        bankAccount.setBankName(request.bankName().trim());
        bankAccount.setHolderName(request.holderName().trim());
        bankAccount.setAccountType(request.accountType());
        bankAccount.setCurrency(request.currency());
        bankAccount.setAlias(alias);
        bankAccount.setBranchIds(request.branchIds());
        bankAccount.setTransferInstructions(normalize(request.transferInstructions()));
        bankAccount.setStatus(request.status());

        BankAccount saved = bankAccountRepository.save(bankAccount);
        return BankAccountResponse.from(saved);
    }

    public void archiveBankAccount(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        BankAccount bankAccount = bankAccountRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Cuenta bancaria no encontrada."));
        bankAccount.setStatus(BankAccountStatus.archived);
        bankAccountRepository.save(bankAccount);
    }

    private void ensureBranchesBelongToTenant(UUID tenantId, List<UUID> branchIds) {
        if (branchIds == null) {
            return;
        }
        for (UUID branchId : branchIds) {
            branchRepository
                    .findByTenantIdAndId(tenantId, branchId)
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "La sucursal asignada no existe."));
        }
    }

    private static String normalizeAccountNumber(String accountNumber) {
        String normalized = accountNumber.replaceAll("[\\s-]", "");
        if (!normalized.matches("[0-9]+") || normalized.length() > 24) {
            throw BusinessException.badRequest("El número de cuenta no es válido.");
        }
        return normalized;
    }

    private static String mask(String accountNumber) {
        if (accountNumber.length() <= 4) {
            return accountNumber;
        }
        String lastFour = accountNumber.substring(accountNumber.length() - 4);
        return "*".repeat(accountNumber.length() - 4) + lastFour;
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
