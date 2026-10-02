package com.omniretail.backend.catalog.service;

import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductKitComponentRequest;
import com.omniretail.backend.catalog.dto.ProductKitComponentResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductKitComponentsRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductKitComponent;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.ProductKitComponentRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductKitService {
    private final ProductRepository productRepository;
    private final ProductKitComponentRepository repository;
    private final BusinessConfigService businessConfigService;
    private final TenantCapabilityGuard capabilityGuard;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public List<ProductKitComponentResponse> list(UUID kitId) {
        UUID tenantId = currentUser.require().tenantId();
        requireKit(tenantId, kitId); requireKitCapabilities(tenantId);
        return responses(tenantId, kitId);
    }

    @Transactional
    public List<ProductKitComponentResponse> replace(UUID kitId, ReplaceProductKitComponentsRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Product kit = requireKitForUpdate(tenantId, kitId); requireKitCapabilities(tenantId);
        if (request.components() == null) throw invalid("La lista de componentes es requerida.");
        if (kit.getStatus() == ProductStatus.published && request.components().isEmpty())
            throw BusinessException.conflict("KIT_COMPONENTS_REQUIRED", "Un kit publicado debe conservar al menos un componente.");
        Set<UUID> ids = new HashSet<>();
        for (ProductKitComponentRequest component : request.components()) {
            if (component == null || component.componentProductId() == null || component.quantityPerKit() == null
                    || component.quantityPerKit().signum() <= 0 || component.quantityPerKit().scale() > 3)
                throw invalid("Los componentes del kit no son validos.");
            if (component.componentProductId().equals(kitId)) throw invalid("Un kit no puede incluirse a si mismo.");
            if (!ids.add(component.componentProductId())) throw invalid("Un componente no puede repetirse.");
        }
        Map<UUID, Product> products = new HashMap<>();
        if (!ids.isEmpty()) {
            productRepository.findAllForUpdateByTenantIdAndIdIn(tenantId, ids)
                    .forEach(product -> products.put(product.getId(), product));
        }
        if (products.size() != ids.size()) throw productNotFound();
        products.values().forEach(ProductKitService::requireEligibleComponent);

        repository.deleteByTenantIdAndKitProductId(tenantId, kitId);
        repository.flush();
        List<ProductKitComponent> values = request.components().stream().map(input -> {
            ProductKitComponent value = ProductKitComponent.builder().kitProductId(kitId)
                    .componentProductId(input.componentProductId()).quantityPerKit(input.quantityPerKit()).build();
            value.setTenantId(tenantId); return value;
        }).toList();
        if (!values.isEmpty()) repository.saveAllAndFlush(values);
        return responses(tenantId, kitId);
    }

    public void validatePublishable(UUID tenantId, Product kit) {
        requireKitCapabilities(tenantId);
        List<ProductKitComponent> components = repository
                .findByTenantIdAndKitProductIdOrderByCreatedAtAscIdAsc(tenantId, kit.getId());
        if (components.isEmpty()) throw BusinessException.conflict("KIT_COMPONENTS_REQUIRED", "El kit requiere al menos un componente valido.");
        Map<UUID, Product> products = new HashMap<>();
        productRepository.findAllForUpdateByTenantIdAndIdIn(tenantId, components.stream()
                .map(ProductKitComponent::getComponentProductId).toList())
                .forEach(product -> products.put(product.getId(), product));
        if (products.size() != components.size()) throw productNotFound();
        products.values().forEach(ProductKitService::requireEligibleComponent);
    }

    @Transactional(readOnly = true)
    public List<FulfillmentComponent> fulfillment(UUID tenantId, Product product, BigDecimal commercialQuantity) {
        if (product.getProductType() != ProductType.kit) return List.of();
        List<ProductKitComponent> components = repository
                .findByTenantIdAndKitProductIdOrderByCreatedAtAscIdAsc(tenantId, product.getId());
        if (components.isEmpty()) throw BusinessException.conflict("KIT_COMPONENTS_REQUIRED", "El kit no tiene componentes configurados.");
        Map<UUID, Product> products = new HashMap<>();
        productRepository.findByTenantIdAndIdIn(tenantId, components.stream()
                .map(ProductKitComponent::getComponentProductId).toList())
                .forEach(component -> products.put(component.getId(), component));
        if (products.size() != components.size()) throw productNotFound();
        products.values().forEach(ProductKitService::requireEligibleComponent);
        try {
            return components.stream().map(value -> new FulfillmentComponent(value.getComponentProductId(),
                    value.getQuantityPerKit(), commercialQuantity.multiply(value.getQuantityPerKit())
                            .setScale(3, RoundingMode.UNNECESSARY))).toList();
        } catch (ArithmeticException exception) {
            throw invalid("La cantidad resultante de un componente excede la precision de inventario.");
        }
    }

    private List<ProductKitComponentResponse> responses(UUID tenantId, UUID kitId) {
        List<ProductKitComponent> values = repository.findByTenantIdAndKitProductIdOrderByCreatedAtAscIdAsc(tenantId, kitId);
        Map<UUID, Product> products = new HashMap<>();
        productRepository.findByTenantIdAndIdIn(tenantId, values.stream().map(ProductKitComponent::getComponentProductId).toList())
                .forEach(product -> products.put(product.getId(), product));
        return values.stream().map(value -> ProductKitComponentResponse.from(value, products.get(value.getComponentProductId()))).toList();
    }
    private Product requireKit(UUID tenantId, UUID id) {
        Product product = productRepository.findByTenantIdAndId(tenantId, id).orElseThrow(ProductKitService::productNotFound);
        if (product.getProductType() != ProductType.kit) throw invalid("El producto no es un kit.");
        return product;
    }
    private Product requireKitForUpdate(UUID tenantId, UUID id) {
        Product product = productRepository.findForUpdateByTenantIdAndId(tenantId, id)
                .orElseThrow(ProductKitService::productNotFound);
        if (product.getProductType() != ProductType.kit) throw invalid("El producto no es un kit.");
        return product;
    }
    private void requireKitCapabilities(UUID tenantId) {
        if (!businessConfigService.getConfig().supportsKits())
            throw BusinessException.forbidden("BUSINESS_CAPABILITY_DISABLED", "El negocio no tiene habilitados los kits.");
        capabilityGuard.ensureTenantCapability(tenantId, SaasCapability.catalogKits);
    }
    private static void requireEligibleComponent(Product product) {
        if (product.getProductType() != ProductType.physical || product.getStatus() != ProductStatus.published
                || !Boolean.TRUE.equals(product.getTrackingStock()))
            throw BusinessException.conflict("KIT_COMPONENT_INVALID", "Cada componente debe ser un producto fisico publicado con control de inventario.");
    }
    private static BusinessException productNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "KIT_COMPONENT_INVALID", message);
    }
    public record FulfillmentComponent(UUID productId, BigDecimal quantityPerKit, BigDecimal quantity) { }
}
