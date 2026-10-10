package com.omniretail.backend.administration.controller;

import com.omniretail.backend.administration.dto.BankAccountResponse;
import com.omniretail.backend.administration.dto.CreateBankAccountRequest;
import com.omniretail.backend.administration.dto.UpdateBankAccountRequest;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import com.omniretail.backend.administration.service.BankAccountService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/administration/bank-accounts")
@RequiredArgsConstructor
@Tag(name = "Cuentas bancarias", description = "Gestión de cuentas bancarias del negocio para cobros, pagos y conciliaciones.")
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @RequirePermission("admin.bank_accounts.manage")
    @GetMapping
    @Operation(
            summary = "Listar cuentas bancarias paginadas",
            description = "Devuelve las cuentas bancarias registradas en el negocio con soporte de paginación y filtro opcional por estado.")
    public PageResponse<BankAccountResponse> list(
            @RequestParam(required = false) BankAccountStatus status, @PageableDefault(size = 20) Pageable pageable) {
        return bankAccountService.listBankAccounts(status, pageable);
    }

    @RequirePermission("admin.bank_accounts.manage")
    @GetMapping("/active")
    @Operation(
            summary = "Listar cuentas bancarias activas",
            description = "Devuelve únicamente las cuentas bancarias activas para selectores de métodos de pago y cobro.")
    public List<BankAccountResponse> listActive() {
        return bankAccountService.listActiveBankAccounts();
    }

    @RequirePermission("admin.bank_accounts.manage")
    @GetMapping("/{id}")
    @Operation(
            summary = "Consultar cuenta bancaria por ID",
            description = "Obtiene los detalles completos de una cuenta bancaria (banco, número, tipo y moneda).")
    public BankAccountResponse getById(@PathVariable UUID id) {
        return bankAccountService.getBankAccountById(id);
    }

    @RequirePermission("admin.bank_accounts.manage")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Crear cuenta bancaria",
            description = "Registra una nueva cuenta bancaria para el negocio.")
    public BankAccountResponse create(@Valid @RequestBody CreateBankAccountRequest request) {
        return bankAccountService.createBankAccount(request);
    }

    @RequirePermission("admin.bank_accounts.manage")
    @PutMapping("/{id}")
    @Operation(
            summary = "Actualizar cuenta bancaria",
            description = "Modifica los datos de una cuenta bancaria existente.")
    public BankAccountResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBankAccountRequest request) {
        return bankAccountService.updateBankAccount(id, request);
    }

    @RequirePermission("admin.bank_accounts.manage")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Archivar cuenta bancaria",
            description = "Aplica borrado lógico archivando la cuenta bancaria.")
    public void archive(@PathVariable UUID id) {
        bankAccountService.archiveBankAccount(id);
    }
}
