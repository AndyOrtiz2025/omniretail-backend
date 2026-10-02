package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ProductSalesPriceTierRequest;
import com.omniretail.backend.catalog.dto.ProductSalesPriceTierResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductSalesPriceTiersRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductSalesPriceTier;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductSalesPriceTierRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductSalesPriceTierService {
    private final ProductRepository productRepository;
    private final ProductSalesPriceTierRepository repository;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<ProductSalesPriceTierResponse> list(UUID productId) {
        UUID tenantId = currentUser.require().tenantId();
        requireProduct(tenantId, productId);
        return repository.findByTenantIdAndProductIdOrderByMinQuantityAsc(tenantId, productId)
                .stream().map(ProductSalesPriceTierResponse::from).toList();
    }

    @Transactional
    public List<ProductSalesPriceTierResponse> replace(UUID productId, ReplaceProductSalesPriceTiersRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Product product = requireProductForUpdate(tenantId, productId);
        if (product.getStatus() == ProductStatus.archived) {
            throw BusinessException.conflict("PRODUCT_ARCHIVED", "Restaura el producto para modificar sus precios por cantidad.");
        }
        validate(request.tiers());
        repository.deleteByTenantIdAndProductId(tenantId, productId);
        repository.flush();
        List<ProductSalesPriceTier> replacements = request.tiers().stream().map(tier -> {
            ProductSalesPriceTier entity = ProductSalesPriceTier.builder().productId(productId)
                    .minQuantity(tier.minQuantity()).unitPrice(tier.unitPrice())
                    .active(tier.active() == null || tier.active()).build();
            entity.setTenantId(tenantId);
            return entity;
        }).toList();
        if (replacements.isEmpty()) return List.of();
        return repository.saveAllAndFlush(replacements).stream()
                .sorted(java.util.Comparator.comparing(ProductSalesPriceTier::getMinQuantity))
                .map(ProductSalesPriceTierResponse::from).toList();
    }

    private static void validate(List<ProductSalesPriceTierRequest> tiers) {
        if (tiers == null) throw invalid();
        Set<Integer> quantities = new HashSet<>();
        for (ProductSalesPriceTierRequest tier : tiers) {
            if (tier == null || tier.minQuantity() == null || tier.minQuantity() <= 1
                    || tier.minQuantity() > 999999 || tier.unitPrice() == null
                    || tier.unitPrice().signum() <= 0 || tier.unitPrice().compareTo(new java.math.BigDecimal("9999999.99")) > 0
                    || Math.max(tier.unitPrice().stripTrailingZeros().scale(), 0) > 2) throw invalid();
            if (!quantities.add(tier.minQuantity())) {
                throw BusinessException.conflict("PRODUCT_PRICE_TIER_CONFLICT", "Las cantidades minimas no pueden repetirse.");
            }
        }
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository.findByTenantIdAndId(tenantId, productId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }
    private Product requireProductForUpdate(UUID tenantId, UUID productId) {
        return productRepository.findForUpdateByTenantIdAndId(tenantId, productId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado."));
    }
    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_PRICE_TIER_INVALID", "Las escalas de precio no son validas.");
    }
}
