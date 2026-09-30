package com.omniretail.backend.catalog.service;

import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductPriceHistoryResponse;
import com.omniretail.backend.catalog.dto.UpdateProductPriceRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductPriceHistory;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.ProductPriceHistoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductPricingService {

    private final ProductRepository productRepository;
    private final ProductPriceHistoryRepository priceHistoryRepository;
    private final CurrentUser currentUser;

    @Transactional
    public ProductDto updatePrice(UUID productId, UpdateProductPriceRequest request) {
        AuthenticatedUser actor = currentUser.require();
        Product product = productRepository
                .findForUpdateByTenantIdAndId(actor.tenantId(), productId)
                .orElseThrow(ProductPricingService::productNotFound);
        if (product.getStatus() == ProductStatus.archived) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVED", "Un producto archivado no puede editarse.");
        }

        BigDecimal newPrice = request.salePrice();
        if (newPrice == null
                || newPrice.compareTo(BigDecimal.ZERO) < 0
                || newPrice.compareTo(new BigDecimal("9999999.99")) > 0
                || newPrice.scale() > 2) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "PRODUCT_PRICE_INVALID", "El precio de venta no es valido.");
        }
        String reason = normalizeReason(request.reason());
        if (product.getSalePrice().compareTo(newPrice) == 0) {
            return ProductService.toDto(product);
        }

        BigDecimal oldPrice = product.getSalePrice();
        priceHistoryRepository.save(ProductPriceHistory.builder()
                .tenantId(actor.tenantId())
                .productId(product.getId())
                .oldPrice(oldPrice)
                .newPrice(newPrice)
                .changedByUserId(actor.userId())
                .reason(reason)
                .build());
        product.setSalePrice(newPrice);
        return ProductService.toDto(productRepository.saveAndFlush(product));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductPriceHistoryResponse> history(UUID productId, Pageable pageable) {
        UUID tenantId = currentUser.require().tenantId();
        if (!productRepository.findByTenantIdAndId(tenantId, productId).isPresent()) {
            throw productNotFound();
        }
        return PageResponse.from(
                priceHistoryRepository.findByTenantIdAndProductIdOrderByCreatedAtDesc(
                        tenantId, productId, pageable),
                ProductPriceHistoryResponse::from);
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String normalized = reason.trim();
        if (normalized.length() > 200) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "PRODUCT_PRICE_REASON_INVALID",
                    "El motivo del cambio de precio no puede exceder 200 caracteres.");
        }
        return normalized;
    }

    private static BusinessException productNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
}
