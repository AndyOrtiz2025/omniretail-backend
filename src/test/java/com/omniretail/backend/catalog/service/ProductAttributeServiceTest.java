package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductAttributeValueRequest;
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
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductAttributeServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID TEXT = UUID.randomUUID();
    private static final UUID NUMBER = UUID.randomUUID();
    private static final UUID BOOLEAN = UUID.randomUUID();

    @Mock private ProductRepository productRepository;
    @Mock private AttributeDefinitionRepository definitionRepository;
    @Mock private ProductAttributeValueRepository valueRepository;
    @Mock private BusinessConfigService businessConfigService;
    @Mock private CurrentUser currentUser;

    private ProductAttributeService service;

    @BeforeEach
    void setUp() {
        service = new ProductAttributeService(
                productRepository,
                definitionRepository,
                valueRepository,
                businessConfigService,
                currentUser);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), TENANT, UserType.employee, null, null, UUID.randomUUID()));
    }

    @Test
    void replacementNormalizesEveryDataTypeAndDeletesOnlyActiveValues() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(definitionRepository.findByTenantIdAndIdIn(any(), any()))
                .willReturn(List.of(
                        definition(TEXT, "text", AttributeDataType.TEXT, AttributeDefinitionStatus.active),
                        definition(NUMBER, "number", AttributeDataType.NUMBER, AttributeDefinitionStatus.active),
                        definition(BOOLEAN, "boolean", AttributeDataType.BOOLEAN, AttributeDefinitionStatus.active)));

        service.replace(PRODUCT, new ReplaceProductAttributesRequest(List.of(
                new ProductAttributeValueRequest(TEXT, "  Azul  "),
                new ProductAttributeValueRequest(NUMBER, "001.2300"),
                new ProductAttributeValueRequest(BOOLEAN, "TRUE"))));

        verify(valueRepository).deleteActiveValues(TENANT, PRODUCT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductAttributeValue>> captor = ArgumentCaptor.forClass(List.class);
        verify(valueRepository).saveAllAndFlush(captor.capture());
        assertThat(captor.getValue())
                .extracting(ProductAttributeValue::getValueString)
                .containsExactly("Azul", "1.23", "true");
        assertThat(captor.getValue()).allSatisfy(value -> assertThat(value.getTenantId()).isEqualTo(TENANT));
    }

    @Test
    void duplicateIdsAreRejectedBeforeDeleteOrInsert() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(true));

        assertCode(() -> service.replace(PRODUCT, new ReplaceProductAttributesRequest(List.of(
                new ProductAttributeValueRequest(TEXT, "a"),
                new ProductAttributeValueRequest(TEXT, "b")))), "PRODUCT_ATTRIBUTE_VALUE_INVALID");

        verify(valueRepository, never()).deleteActiveValues(any(), any());
        verify(valueRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void replacementRequiresBusinessAttributesCapability() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(false));

        assertCode(
                () -> service.replace(PRODUCT, new ReplaceProductAttributesRequest(List.of())),
                "PRODUCT_CAPABILITY_DISABLED");

        verify(valueRepository, never()).deleteActiveValues(any(), any());
    }

    @Test
    void emptyReplacementRemovesActiveValuesWithoutTouchingArchivedValues() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(true));

        assertThat(service.replace(PRODUCT, new ReplaceProductAttributesRequest(List.of()))).isEmpty();

        verify(valueRepository).deleteActiveValues(TENANT, PRODUCT);
        verify(valueRepository).saveAllAndFlush(List.of());
    }

    @Test
    void archivedDefinitionsAndInvalidValuesAreRejected() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(definitionRepository.findByTenantIdAndIdIn(any(), any()))
                .willReturn(List.of(definition(
                        TEXT, "archived", AttributeDataType.TEXT, AttributeDefinitionStatus.archived)));
        assertCode(
                () -> service.replace(PRODUCT, request(TEXT, "value")),
                "ATTRIBUTE_DEFINITION_ARCHIVED");

        given(definitionRepository.findByTenantIdAndIdIn(any(), any()))
                .willReturn(List.of(definition(
                        NUMBER, "number", AttributeDataType.NUMBER, AttributeDefinitionStatus.active)));
        assertCode(
                () -> service.replace(PRODUCT, request(NUMBER, "NaN")),
                "PRODUCT_ATTRIBUTE_VALUE_INVALID");
    }

    @Test
    void nullValueAndCrossTenantDefinitionAreRejectedBeforeMutation() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(definitionRepository.findByTenantIdAndIdIn(any(), any()))
                .willReturn(List.of(definition(
                        TEXT, "text", AttributeDataType.TEXT, AttributeDefinitionStatus.active)));
        assertCode(
                () -> service.replace(PRODUCT, request(TEXT, null)),
                "PRODUCT_ATTRIBUTE_VALUE_INVALID");

        UUID foreign = UUID.randomUUID();
        given(definitionRepository.findByTenantIdAndIdIn(any(), any())).willReturn(List.of());
        assertCode(
                () -> service.replace(PRODUCT, request(foreign, "value")),
                "ATTRIBUTE_DEFINITION_NOT_FOUND");
        verify(valueRepository, never()).deleteActiveValues(any(), any());
    }

    @Test
    void textLengthIsMeasuredAfterTrim() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        given(businessConfigService.getConfig()).willReturn(config(true));
        given(definitionRepository.findByTenantIdAndIdIn(any(), any()))
                .willReturn(List.of(definition(
                        TEXT, "text", AttributeDataType.TEXT, AttributeDefinitionStatus.active)));

        var accepted = service.replace(PRODUCT, request(TEXT, "  " + "x".repeat(500) + "  "));
        assertThat(accepted).singleElement().satisfies(item -> assertThat(item.value()).hasSize(500));

        assertCode(
                () -> service.replace(PRODUCT, request(TEXT, "x".repeat(501))),
                "PRODUCT_ATTRIBUTE_VALUE_INVALID");
    }

    @Test
    void getAllowsDisabledCapabilityAndReturnsArchivedHistoricalValues() {
        given(productRepository.findByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.published)));
        ProductAttributeValue value = ProductAttributeValue.builder()
                .productId(PRODUCT)
                .attributeDefinitionId(TEXT)
                .valueString("legacy")
                .build();
        value.setTenantId(TENANT);
        given(valueRepository.findByTenantIdAndProductId(TENANT, PRODUCT)).willReturn(List.of(value));
        given(definitionRepository.findByTenantIdAndIdIn(any(), any()))
                .willReturn(List.of(definition(
                        TEXT, "legacy", AttributeDataType.TEXT, AttributeDefinitionStatus.archived)));

        var response = service.get(PRODUCT);

        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(AttributeDefinitionStatus.archived);
            assertThat(item.value()).isEqualTo("legacy");
        });
        verify(businessConfigService, never()).getConfig();
    }

    @Test
    void archivedAndCrossTenantProductsAreRejected() {
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT))
                .willReturn(Optional.of(product(ProductStatus.archived)));
        assertCode(() -> service.replace(PRODUCT, request(TEXT, "value")), "PRODUCT_ARCHIVED");

        UUID foreign = UUID.randomUUID();
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, foreign))
                .willReturn(Optional.empty());
        assertCode(() -> service.replace(foreign, request(TEXT, "value")), "PRODUCT_NOT_FOUND");
    }

    private static ReplaceProductAttributesRequest request(UUID id, String value) {
        return new ReplaceProductAttributesRequest(List.of(new ProductAttributeValueRequest(id, value)));
    }

    private static Product product(ProductStatus status) {
        Product product = Product.builder()
                .sku("SKU")
                .name("Producto")
                .salePrice(BigDecimal.ONE)
                .status(status)
                .build();
        product.setTenantId(TENANT);
        ReflectionTestUtils.setField(product, "id", PRODUCT);
        return product;
    }

    private static AttributeDefinition definition(
            UUID id, String code, AttributeDataType type, AttributeDefinitionStatus status) {
        AttributeDefinition definition = AttributeDefinition.builder()
                .code(code)
                .name(code)
                .dataType(type)
                .status(status)
                .build();
        definition.setTenantId(TENANT);
        ReflectionTestUtils.setField(definition, "id", id);
        return definition;
    }

    private static BusinessConfigResponse config(boolean attributes) {
        return new BusinessConfigResponse(
                TENANT, BusinessPreset.custom, true, true, true, true, true, true,
                attributes, true, true, List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(true, true, true, true));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
