package com.omniretail.backend.catalog.service;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductAttributeValueRequest;
import com.omniretail.backend.catalog.dto.ProductAttributeValueResponse;
import com.omniretail.backend.catalog.dto.ReplaceProductAttributesRequest;
import com.omniretail.backend.catalog.entity.AttributeDataType;
import com.omniretail.backend.catalog.entity.AttributeDefinition;
import com.omniretail.backend.catalog.entity.AttributeDefinitionStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductAttributeValue;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.repository.AttributeDefinitionRepository;
import com.omniretail.backend.catalog.repository.ProductAttributeValueRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductAttributeService {

    private static final String UNIQUE_VALUE_CONSTRAINT =
            "uk_product_attribute_values_tenant_product_definition";

    private final ProductRepository productRepository;
    private final AttributeDefinitionRepository definitionRepository;
    private final ProductAttributeValueRepository valueRepository;
    private final BusinessConfigService businessConfigService;
    private final CurrentUser currentUser;

    public List<ProductAttributeValueResponse> get(UUID productId) {
        UUID tenantId = currentUser.require().tenantId();
        requireProduct(tenantId, productId);
        List<ProductAttributeValue> values = valueRepository.findByTenantIdAndProductId(tenantId, productId);
        if (values.isEmpty()) {
            return List.of();
        }
        Map<UUID, AttributeDefinition> definitions = definitionsById(
                definitionRepository.findByTenantIdAndIdIn(
                        tenantId,
                        values.stream().map(ProductAttributeValue::getAttributeDefinitionId).toList()));
        return values.stream()
                .map(value -> toResponse(value, definitions.get(value.getAttributeDefinitionId())))
                .sorted(Comparator.comparing(ProductAttributeValueResponse::code))
                .toList();
    }

    @Transactional
    public List<ProductAttributeValueResponse> replace(
            UUID productId, ReplaceProductAttributesRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Product product = productRepository
                .findForUpdateByTenantIdAndId(tenantId, productId)
                .orElseThrow(ProductAttributeService::productNotFound);
        if (product.getStatus() == ProductStatus.archived) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVED", "Un producto archivado no puede editarse.");
        }
        requireAttributesCapability();

        List<ProductAttributeValueRequest> requested = request == null ? null : request.attributes();
        if (requested == null) {
            throw invalidValue("La lista de atributos es requerida.");
        }
        Set<UUID> requestedIds = new HashSet<>();
        for (ProductAttributeValueRequest item : requested) {
            if (item == null || item.attributeId() == null) {
                throw invalidValue("Cada atributo debe incluir un identificador.");
            }
            if (!requestedIds.add(item.attributeId())) {
                throw invalidValue("Un atributo no puede aparecer mas de una vez.");
            }
        }

        Map<UUID, AttributeDefinition> definitions = requestedIds.isEmpty()
                ? Map.of()
                : definitionsById(definitionRepository.findByTenantIdAndIdIn(tenantId, requestedIds));
        if (definitions.size() != requestedIds.size()) {
            throw definitionNotFound();
        }

        List<ProductAttributeValue> replacements = new ArrayList<>(requested.size());
        for (ProductAttributeValueRequest item : requested) {
            AttributeDefinition definition = definitions.get(item.attributeId());
            if (definition.getStatus() != AttributeDefinitionStatus.active) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "ATTRIBUTE_DEFINITION_ARCHIVED",
                        "No se puede asignar un atributo archivado.");
            }
            ProductAttributeValue value = ProductAttributeValue.builder()
                    .productId(productId)
                    .attributeDefinitionId(definition.getId())
                    .valueString(normalizeValue(definition.getDataType(), item.value()))
                    .build();
            value.setTenantId(tenantId);
            replacements.add(value);
        }

        valueRepository.deleteActiveValues(tenantId, productId);
        try {
            valueRepository.saveAllAndFlush(replacements);
        } catch (DataIntegrityViolationException exception) {
            if (exceptionDetail(exception).contains(UNIQUE_VALUE_CONSTRAINT)) {
                throw BusinessException.conflict(
                        "PRODUCT_ATTRIBUTE_VALUE_CONFLICT",
                        "El producto ya tiene un valor para uno de los atributos.");
            }
            throw exception;
        }
        return replacements.stream()
                .map(value -> toResponse(value, definitions.get(value.getAttributeDefinitionId())))
                .sorted(Comparator.comparing(ProductAttributeValueResponse::code))
                .toList();
    }

    private Product requireProduct(UUID tenantId, UUID productId) {
        return productRepository.findByTenantIdAndId(tenantId, productId)
                .orElseThrow(ProductAttributeService::productNotFound);
    }

    private void requireAttributesCapability() {
        BusinessConfigResponse config = businessConfigService.getConfig();
        if (!config.supportsProductAttributes()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "PRODUCT_CAPABILITY_DISABLED",
                    "El negocio no tiene habilitados los atributos de producto.");
        }
    }

    private static Map<UUID, AttributeDefinition> definitionsById(
            List<AttributeDefinition> definitions) {
        Map<UUID, AttributeDefinition> result = new HashMap<>();
        for (AttributeDefinition definition : definitions) {
            result.put(definition.getId(), definition);
        }
        return result;
    }

    private static ProductAttributeValueResponse toResponse(
            ProductAttributeValue value, AttributeDefinition definition) {
        if (definition == null) {
            throw definitionNotFound();
        }
        return new ProductAttributeValueResponse(
                definition.getId(),
                definition.getCode(),
                definition.getName(),
                definition.getDataType(),
                definition.getStatus(),
                value.getValueString());
    }

    private static String normalizeValue(AttributeDataType type, String value) {
        if (value == null) {
            throw invalidValue("El valor del atributo es requerido.");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw invalidValue("El valor del atributo no puede estar vacio.");
        }
        return switch (type) {
            case TEXT -> requireMaxLength(normalized);
            case NUMBER -> normalizeNumber(normalized);
            case BOOLEAN -> normalizeBoolean(normalized);
        };
    }

    private static String normalizeNumber(String value) {
        if (value.length() > 500) {
            throw invalidValue("El valor numerico no es valido.");
        }
        final BigDecimal number;
        try {
            number = new BigDecimal(value).stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw invalidValue("El valor numerico no es valido.");
        }
        int signLength = number.signum() < 0 ? 1 : 0;
        long plainLength = number.scale() <= 0
                ? (long) number.precision() - number.scale() + signLength
                : (long) Math.max(number.precision() - number.scale(), 1)
                        + 1L + number.scale() + signLength;
        if (plainLength > 500) {
            throw invalidValue("El valor numerico no puede exceder 500 caracteres.");
        }
        return number.toPlainString();
    }

    private static String normalizeBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(value)) {
            return "false";
        }
        throw invalidValue("El valor booleano debe ser true o false.");
    }

    private static String requireMaxLength(String value) {
        if (value.length() > 500) {
            throw invalidValue("El valor del atributo no puede exceder 500 caracteres.");
        }
        return value;
    }

    private static BusinessException invalidValue(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_ATTRIBUTE_VALUE_INVALID", message);
    }

    private static BusinessException definitionNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "ATTRIBUTE_DEFINITION_NOT_FOUND", "Atributo no encontrado.");
    }

    private static BusinessException productNotFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Producto no encontrado.");
    }

    private static String exceptionDetail(Throwable exception) {
        StringBuilder detail = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                detail.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
        }
        return detail.toString();
    }
}
