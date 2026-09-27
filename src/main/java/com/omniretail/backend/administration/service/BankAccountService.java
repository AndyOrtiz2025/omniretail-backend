package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.BankAccountResponse;
import com.omniretail.backend.administration.dto.CreateBankAccountRequest;
import com.omniretail.backend.administration.dto.UpdateBankAccountRequest;
import com.omniretail.backend.administration.entity.BankAccount;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.repository.BankAccountRepository;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

        String accountNumber = normalizeAccountNumber(request.accountNumber());
        if (bankAccountRepository.existsByTenantIdAndAccountNumber(tenantId, accountNumber)) {
            throw accountNumberExists();
        }

        List<UUID> branchIds = normalizeBranchIds(request.branchIds());
        ensureActiveBranches(tenantId, branchIds, Set.of());

        BankAccount bankAccount = BankAccount.builder()
                .bankName(request.bankName().trim())
                .holderName(request.holderName().trim())
                .accountNumber(accountNumber)
                .accountNumberMasked(mask(accountNumber))
                .accountType(request.accountType())
                .currency(request.currency())
                .alias(request.alias().trim())
                .branchIds(branchIds)
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

        List<UUID> branchIds = normalizeBranchIds(request.branchIds());
        Set<UUID> previousBranchIds =
                bankAccount.getBranchIds() != null ? new HashSet<>(bankAccount.getBranchIds()) : Set.of();
        ensureActiveBranches(tenantId, branchIds, previousBranchIds);

        if (request.accountNumber() != null && !request.accountNumber().isBlank()) {
            String accountNumber = normalizeAccountNumber(request.accountNumber());
            if (bankAccountRepository.existsByTenantIdAndAccountNumberAndIdNot(tenantId, accountNumber, id)) {
                throw accountNumberExists();
            }
            bankAccount.setAccountNumber(accountNumber);
            bankAccount.setAccountNumberMasked(mask(accountNumber));
        }

        bankAccount.setBankName(request.bankName().trim());
        bankAccount.setHolderName(request.holderName().trim());
        bankAccount.setAccountType(request.accountType());
        bankAccount.setCurrency(request.currency());
        bankAccount.setAlias(request.alias().trim());
        bankAccount.setBranchIds(branchIds);
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

    /**
     * Sucursales ya asignadas a la cuenta se conservan aunque hoy estén inactivas
     * (ensureBankAccountBranchIds en el frontend); las nuevas deben existir en el tenant y estar
     * activas.
     */
    private void ensureActiveBranches(UUID tenantId, List<UUID> branchIds, Set<UUID> alreadyAssigned) {
        for (UUID branchId : branchIds) {
            Branch branch = branchRepository
                    .findByTenantIdAndId(tenantId, branchId)
                    .orElseThrow(() -> new BusinessException(
                            HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "La sucursal asignada no existe."));
            if (!alreadyAssigned.contains(branchId) && branch.getStatus() != BranchStatus.active) {
                throw BusinessException.conflict("BRANCH_INACTIVE", "La sucursal asignada no está activa.");
            }
        }
    }

    /** branchIds nunca queda en null: el frontend siempre espera una lista, sin duplicados. */
    private static List<UUID> normalizeBranchIds(List<UUID> branchIds) {
        return branchIds != null ? new ArrayList<>(new LinkedHashSet<>(branchIds)) : new ArrayList<>();
    }

    /** Incluye cuentas archivadas: se reactivan editándolas, no creando una nueva. */
    private static BusinessException accountNumberExists() {
        return BusinessException.conflict(
                "BANK_ACCOUNT_NUMBER_EXISTS", "Ya existe una cuenta bancaria con ese número de cuenta.");
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
