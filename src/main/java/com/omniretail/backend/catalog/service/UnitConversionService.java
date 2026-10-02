package com.omniretail.backend.catalog.service;

import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.UnitConversionCreateRequest;
import com.omniretail.backend.catalog.dto.UnitConversionResponse;
import com.omniretail.backend.catalog.dto.UnitConversionUpdateRequest;
import com.omniretail.backend.catalog.dto.ReplaceProductUnitConversionsRequest;
import com.omniretail.backend.catalog.dto.ProductUnitConversionRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.UnitConversion;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnitConversionService {

    private static final String CONVERSION_NOT_FOUND =
            "Conversión de unidad no encontrada.";

    private final UnitConversionRepository unitConversionRepository;
    private final UnitRepository unitRepository;
    private final ProductRepository productRepository;
    private final BusinessConfigService businessConfigService;
    private final CurrentUser currentUser;

    public PageResponse<UnitConversionResponse> list(
            UUID productId,
            UUID fromUnitId,
            UUID toUnitId,
            Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        Page<UnitConversion> conversions = unitConversionRepository.findAllFiltered(
                tenantId, productId, fromUnitId, toUnitId, pageable);
        return PageResponse.from(conversions, UnitConversionResponse::from);
    }

    public UnitConversionResponse getById(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        return UnitConversionResponse.from(requireConversion(tenantId, id));
    }

    public List<UnitConversionResponse> listForProduct(UUID productId) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        validateProduct(tenantId, productId);
        return unitConversionRepository.findByTenantIdAndProductId(tenantId, productId).stream()
                .sorted(java.util.Comparator.comparing(UnitConversion::getFromUnitId)
                        .thenComparing(UnitConversion::getToUnitId))
                .map(UnitConversionResponse::from).toList();
    }

    @Transactional
    public List<UnitConversionResponse> replaceForProduct(
            UUID productId, ReplaceProductUnitConversionsRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        Product product = requireProductForUpdate(tenantId, productId);
        if (product.getStatus() == ProductStatus.archived) {
            throw BusinessException.conflict("PRODUCT_ARCHIVED", "Restaura el producto para modificar conversiones.");
        }
        if (request.conversions() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNIT_CONVERSION_INVALID", "La lista de conversiones es requerida.");
        }
        Set<String> pairs = new HashSet<>();
        Set<UUID> productUnits = new HashSet<>();
        productUnits.add(product.getBaseUnitId());
        if (product.getInventoryUnitId() != null) productUnits.add(product.getInventoryUnitId());
        if (product.getSaleUnitId() != null) productUnits.add(product.getSaleUnitId());
        for (ProductUnitConversionRequest conversion : request.conversions()) {
            validateDifferentUnits(conversion.fromUnitId(), conversion.toUnitId());
            validateActiveUnit(tenantId, conversion.fromUnitId(), "UNIT_CONVERSION_FROM_UNIT_NOT_FOUND", "Unidad origen no encontrada o inactiva.");
            validateActiveUnit(tenantId, conversion.toUnitId(), "UNIT_CONVERSION_TO_UNIT_NOT_FOUND", "Unidad destino no encontrada o inactiva.");
            if (!productUnits.contains(conversion.fromUnitId()) || !productUnits.contains(conversion.toUnitId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "UNIT_CONVERSION_PRODUCT_UNIT_MISMATCH",
                        "Las conversiones solo pueden usar unidades configuradas en el producto.");
            }
            if (!pairs.add(conversion.fromUnitId() + ":" + conversion.toUnitId())) {
                throw BusinessException.conflict("UNIT_CONVERSION_CONFLICT", "No pueden repetirse pares de unidades.");
            }
        }
        unitConversionRepository.deleteByTenantIdAndProductId(tenantId, productId);
        unitConversionRepository.flush();
        List<UnitConversion> replacements = request.conversions().stream().map(input -> UnitConversion.builder()
                .tenantId(tenantId).productId(productId).fromUnitId(input.fromUnitId())
                .toUnitId(input.toUnitId()).factor(input.factor()).build()).toList();
        if (replacements.isEmpty()) return List.of();
        return unitConversionRepository.saveAllAndFlush(replacements).stream()
                .sorted(java.util.Comparator.comparing(UnitConversion::getFromUnitId)
                        .thenComparing(UnitConversion::getToUnitId))
                .map(UnitConversionResponse::from).toList();
    }

    @Transactional
    public UnitConversionResponse create(UnitConversionCreateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        validateDifferentUnits(request.fromUnitId(), request.toUnitId());
        validateActiveUnit(
                tenantId,
                request.fromUnitId(),
                "UNIT_CONVERSION_FROM_UNIT_NOT_FOUND",
                "Unidad origen no encontrada o inactiva.");
        validateActiveUnit(
                tenantId,
                request.toUnitId(),
                "UNIT_CONVERSION_TO_UNIT_NOT_FOUND",
                "Unidad destino no encontrada o inactiva.");
        validateProduct(tenantId, request.productId());
        validateNotDuplicate(
                tenantId,
                request.productId(),
                request.fromUnitId(),
                request.toUnitId());

        UnitConversion conversion = UnitConversion.builder()
                .tenantId(tenantId)
                .productId(request.productId())
                .fromUnitId(request.fromUnitId())
                .toUnitId(request.toUnitId())
                .factor(request.factor())
                .build();
        return UnitConversionResponse.from(
                unitConversionRepository.saveAndFlush(conversion));
    }

    @Transactional
    public UnitConversionResponse update(UUID id, UnitConversionUpdateRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        UnitConversion conversion = requireConversion(tenantId, id);
        conversion.setFactor(request.factor());
        return UnitConversionResponse.from(
                unitConversionRepository.saveAndFlush(conversion));
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = currentUser.require().tenantId();
        ensureUnitsAndPackagingEnabled();
        UnitConversion conversion = requireConversion(tenantId, id);
        unitConversionRepository.delete(conversion);
        unitConversionRepository.flush();
    }

    private void ensureUnitsAndPackagingEnabled() {
        if (!businessConfigService.getConfig().supportsUnitsAndPackaging()) {
            throw BusinessException.forbidden(
                    "BUSINESS_CAPABILITY_DISABLED",
                    "La gestión de unidades y empaques no está habilitada para este negocio.");
        }
    }

    private UnitConversion requireConversion(UUID tenantId, UUID id) {
        return unitConversionRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "UNIT_CONVERSION_NOT_FOUND",
                        CONVERSION_NOT_FOUND));
    }

    private static void validateDifferentUnits(UUID fromUnitId, UUID toUnitId) {
        if (fromUnitId.equals(toUnitId)) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "UNIT_CONVERSION_SAME_UNIT",
                    "Las unidades origen y destino no pueden ser iguales.");
        }
    }

    private void validateActiveUnit(
            UUID tenantId, UUID unitId, String errorCode, String errorMessage) {
        if (!unitRepository.existsByIdAndTenantIdAndStatus(
                unitId, tenantId, UnitStatus.active)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, errorCode, errorMessage);
        }
    }

    private void validateProduct(UUID tenantId, UUID productId) {
        if (productId != null
                && productRepository.findByTenantIdAndId(tenantId, productId).isEmpty()) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "UNIT_CONVERSION_PRODUCT_NOT_FOUND",
                    "Producto no encontrado.");
        }
    }

    private Product requireProductForUpdate(UUID tenantId, UUID productId) {
        return productRepository.findForUpdateByTenantIdAndId(tenantId, productId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "UNIT_CONVERSION_PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }

    private void validateNotDuplicate(
            UUID tenantId, UUID productId, UUID fromUnitId, UUID toUnitId) {
        boolean exists = productId == null
                ? unitConversionRepository
                        .existsByTenantIdAndProductIdIsNullAndFromUnitIdAndToUnitId(
                                tenantId, fromUnitId, toUnitId)
                : unitConversionRepository
                        .existsByTenantIdAndProductIdAndFromUnitIdAndToUnitId(
                                tenantId, productId, fromUnitId, toUnitId);
        if (exists) {
            throw BusinessException.conflict(
                    "UNIT_CONVERSION_CONFLICT",
                    "Ya existe una conversión para este par de unidades.");
        }
    }
}
