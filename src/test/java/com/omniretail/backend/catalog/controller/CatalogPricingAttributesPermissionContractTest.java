package com.omniretail.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.catalog.dto.AttributeDefinitionCreateRequest;
import com.omniretail.backend.catalog.dto.AttributeDefinitionUpdateRequest;
import com.omniretail.backend.catalog.dto.ReplaceProductAttributesRequest;
import com.omniretail.backend.catalog.dto.UpdateProductPriceRequest;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class CatalogPricingAttributesPermissionContractTest {

    @Test
    void pricingEndpointsUseExistingProductPermissions() throws Exception {
        assertThat(ProductPricingController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/catalog/products");
        assertPermission(
                ProductPricingController.class.getMethod(
                        "updatePrice", UUID.class, UpdateProductPriceRequest.class),
                "catalog.products.update");
        assertPermission(
                ProductPricingController.class.getMethod("history", UUID.class, Pageable.class),
                "catalog.products.read");
        assertThat(ProductPricingController.class
                        .getMethod("updatePrice", UUID.class, UpdateProductPriceRequest.class)
                        .getAnnotation(PutMapping.class).value())
                .containsExactly("/{id}/price");
        assertThat(ProductPricingController.class
                        .getMethod("history", UUID.class, Pageable.class)
                        .getAnnotation(GetMapping.class).value())
                .containsExactly("/{id}/price-history");
    }

    @Test
    void definitionEndpointsUseExactAttributePermissions() throws Exception {
        assertThat(AttributeDefinitionController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/catalog/attributes");
        assertPermission(
                AttributeDefinitionController.class.getMethod("list", Pageable.class),
                "catalog.attributes.read");
        assertPermission(
                AttributeDefinitionController.class.getMethod(
                        "create", AttributeDefinitionCreateRequest.class),
                "catalog.attributes.manage");
        assertPermission(
                AttributeDefinitionController.class.getMethod(
                        "update", UUID.class, AttributeDefinitionUpdateRequest.class),
                "catalog.attributes.manage");
        assertPermission(
                AttributeDefinitionController.class.getMethod("archive", UUID.class),
                "catalog.attributes.manage");
        assertThat(AttributeDefinitionController.class
                        .getMethod("create", AttributeDefinitionCreateRequest.class)
                        .getAnnotation(PostMapping.class))
                .isNotNull();
        assertThat(AttributeDefinitionController.class
                        .getMethod("archive", UUID.class)
                        .getAnnotation(DeleteMapping.class).value())
                .containsExactly("/{id}");
    }

    @Test
    void productAttributeEndpointsUseExistingProductPermissions() throws Exception {
        assertThat(ProductAttributeController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/catalog/products");
        assertPermission(
                ProductAttributeController.class.getMethod("get", UUID.class),
                "catalog.products.read");
        assertPermission(
                ProductAttributeController.class.getMethod(
                        "replace", UUID.class, ReplaceProductAttributesRequest.class),
                "catalog.products.update");
        assertThat(ProductAttributeController.class
                        .getMethod("replace", UUID.class, ReplaceProductAttributesRequest.class)
                        .getAnnotation(PutMapping.class).value())
                .containsExactly("/{id}/attributes");
    }

    private static void assertPermission(Method method, String expected) {
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(expected);
    }
}
