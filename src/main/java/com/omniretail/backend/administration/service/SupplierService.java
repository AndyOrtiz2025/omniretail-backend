package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.CreateSupplierRequest;
import com.omniretail.backend.administration.dto.SupplierResponse;
import com.omniretail.backend.administration.dto.UpdateSupplierRequest;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.repository.SupplierRepository;
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
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final CurrentUser currentUser;

    public PageResponse<SupplierResponse> listSuppliers(SupplierStatus status, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        Page<Supplier> page = status != null
                ? supplierRepository.findByTenantIdAndStatus(tenantId, status, pageable)
                : supplierRepository.findByTenantId(tenantId, pageable);
        return PageResponse.from(page, SupplierResponse::from);
    }

    public List<SupplierResponse> listActiveSuppliers() {
        UUID tenantId = currentUser.require().tenantId();
        return supplierRepository.findByTenantIdAndStatus(tenantId, SupplierStatus.active).stream()
                .map(SupplierResponse::from)
                .toList();
    }

    public SupplierResponse getSupplierById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Supplier supplier = supplierRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));
        return SupplierResponse.from(supplier);
    }

    public SupplierResponse createSupplier(CreateSupplierRequest request) {
        UUID tenantId = currentUser.require().tenantId();

        String name = request.name().trim();
        if (supplierRepository.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw BusinessException.conflict("SUPPLIER_NAME_EXISTS", "Ya existe un proveedor con el nombre " + name);
        }

        String taxId = normalize(request.taxId());
        if (taxId != null && supplierRepository.existsByTenantIdAndTaxIdIgnoreCase(tenantId, taxId)) {
            throw BusinessException.conflict(
                    "SUPPLIER_TAX_ID_EXISTS", "Ya existe un proveedor con la identificación tributaria " + taxId);
        }

        Supplier supplier = Supplier.builder()
                .name(name)
                .legalName(normalize(request.legalName()))
                .taxId(taxId)
                .email(normalizeEmail(request.email()))
                .phone(normalize(request.phone()))
                .address(normalize(request.address()))
                .notes(normalize(request.notes()))
                .status(request.status() != null ? request.status() : SupplierStatus.active)
                .build();
        supplier.setTenantId(tenantId);
        Supplier saved = supplierRepository.save(supplier);
        return SupplierResponse.from(saved);
    }

    public SupplierResponse updateSupplier(UUID id, UpdateSupplierRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Supplier supplier = supplierRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));

        String name = request.name().trim();
        if (!name.equalsIgnoreCase(supplier.getName())
                && supplierRepository.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, id)) {
            throw BusinessException.conflict("SUPPLIER_NAME_EXISTS", "Ya existe un proveedor con el nombre " + name);
        }

        String taxId = normalize(request.taxId());
        if (taxId != null
                && !taxId.equalsIgnoreCase(supplier.getTaxId())
                && supplierRepository.existsByTenantIdAndTaxIdIgnoreCaseAndIdNot(tenantId, taxId, id)) {
            throw BusinessException.conflict(
                    "SUPPLIER_TAX_ID_EXISTS", "Ya existe un proveedor con la identificación tributaria " + taxId);
        }

        supplier.setName(name);
        supplier.setLegalName(normalize(request.legalName()));
        supplier.setTaxId(taxId);
        supplier.setEmail(normalizeEmail(request.email()));
        supplier.setPhone(normalize(request.phone()));
        supplier.setAddress(normalize(request.address()));
        supplier.setNotes(normalize(request.notes()));
        supplier.setStatus(request.status());

        Supplier saved = supplierRepository.save(supplier);
        return SupplierResponse.from(saved);
    }

    public void archiveSupplier(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        Supplier supplier = supplierRepository
                .findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Proveedor no encontrado."));
        supplier.setStatus(SupplierStatus.archived);
        supplierRepository.save(supplier);
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String normalizeEmail(String value) {
        String normalized = normalize(value);
        return normalized != null ? normalized.toLowerCase() : null;
    }
}
