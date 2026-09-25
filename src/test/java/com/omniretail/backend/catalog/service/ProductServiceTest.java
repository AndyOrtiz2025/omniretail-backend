package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UnitRepository unitRepository;

    @Mock
    private CurrentUser currentUser;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, unitRepository, currentUser);
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), TENANT_ID, UserType.employee, null, null, UUID.randomUUID()));
    }

    @Test
    void listUsesAuthenticatedTenantId() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(productRepository.findByTenantId(TENANT_ID, pageable)).willReturn(new PageImpl<>(List.of()));

        service.list(pageable);

        verify(productRepository).findByTenantId(TENANT_ID, pageable);
    }

    @Test
    void listDelegatesPaginationToTenantScopedQuery() {
        PageRequest pageable = PageRequest.of(3, 7);
        given(productRepository.findByTenantId(TENANT_ID, pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 25));

        var response = service.list(pageable);

        assertThat(response.page()).isEqualTo(4);
        assertThat(response.pageSize()).isEqualTo(7);
        assertThat(response.totalItems()).isEqualTo(25);
        verify(productRepository).findByTenantId(TENANT_ID, pageable);
    }

    @Test
    void createMapsNestedTrackingToFlattenedEntityFields() {
        allowValidCreation();
        ProductCreateRequest request = validRequest();

        ProductDto response = service.create(request);

        Product saved = savedProduct();
        assertThat(saved.getTrackingStock()).isEqualTo(request.tracking().stock());
        assertThat(saved.getTrackingLot()).isEqualTo(request.tracking().lot());
        assertThat(saved.getTrackingExpiration()).isEqualTo(request.tracking().expiration());
        assertThat(saved.getTrackingSerial()).isEqualTo(request.tracking().serial());
        assertThat(response.tracking()).isEqualTo(request.tracking());
    }

    @Test
    void createMapsNestedChannelsToFlattenedEntityFields() {
        allowValidCreation();
        ProductCreateRequest request = validRequest();

        ProductDto response = service.create(request);

        Product saved = savedProduct();
        assertThat(saved.getChannelEcommerce()).isEqualTo(request.channels().ecommerce());
        assertThat(saved.getChannelPos()).isEqualTo(request.channels().pos());
        assertThat(saved.getChannelMobileApp()).isEqualTo(request.channels().mobileApp());
        assertThat(response.channels()).isEqualTo(request.channels());
    }

    @Test
    void duplicateSkuInSameTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(productRepository.existsByTenantIdAndSku(TENANT_ID, request.sku())).willReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("PRODUCT_SKU_CONFLICT");
                });
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameSkuInAnotherTenantDoesNotCauseFalseDuplicate() {
        ProductCreateRequest request = validRequest();
        allowValidCreation();
        given(productRepository.existsByTenantIdAndSku(TENANT_ID, request.sku())).willReturn(false);

        service.create(request);

        verify(productRepository).existsByTenantIdAndSku(TENANT_ID, request.sku());
        verify(productRepository).saveAndFlush(any(Product.class));
    }

    @Test
    void categoryFromAnotherTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantId(request.categoryId(), TENANT_ID)).willReturn(false);

        assertNotFound(() -> service.create(request));
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void baseUnitFromAnotherTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantId(request.categoryId(), TENANT_ID)).willReturn(true);
        given(unitRepository.existsByIdAndTenantId(request.baseUnitId(), TENANT_ID)).willReturn(false);

        assertNotFound(() -> service.create(request));
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void optionalInventoryUnitFromAnotherTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantId(request.categoryId(), TENANT_ID)).willReturn(true);
        given(unitRepository.existsByIdAndTenantId(request.baseUnitId(), TENANT_ID)).willReturn(true);
        given(unitRepository.existsByIdAndTenantId(request.inventoryUnitId(), TENANT_ID)).willReturn(false);

        assertNotFound(() -> service.create(request));
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void saleUnitFromAnotherTenantIsRejected() {
        ProductCreateRequest request = requestWithInventoryUnit(null);
        given(categoryRepository.existsByIdAndTenantId(request.categoryId(), TENANT_ID)).willReturn(true);
        given(unitRepository.existsByIdAndTenantId(request.baseUnitId(), TENANT_ID)).willReturn(true);
        given(unitRepository.existsByIdAndTenantId(request.saleUnitId(), TENANT_ID)).willReturn(false);

        assertNotFound(() -> service.create(request));
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void createdEntityReceivesAuthenticatedTenantId() {
        allowValidCreation();

        ProductDto response = service.create(validRequest());

        assertThat(savedProduct().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
    }

    private void allowValidCreation() {
        given(categoryRepository.existsByIdAndTenantId(any(UUID.class), any(UUID.class))).willReturn(true);
        given(unitRepository.existsByIdAndTenantId(any(UUID.class), any(UUID.class))).willReturn(true);
        given(productRepository.saveAndFlush(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    private Product savedProduct() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static void assertNotFound(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static ProductCreateRequest validRequest() {
        return requestWithInventoryUnit(UUID.randomUUID());
    }

    private static ProductCreateRequest requestWithInventoryUnit(UUID inventoryUnitId) {
        return new ProductCreateRequest(
                "SKU-001",
                "123456789",
                "Producto",
                "Descripcion",
                "Marca",
                ProductType.physical,
                UUID.randomUUID(),
                UUID.randomUUID(),
                inventoryUnitId,
                UUID.randomUUID(),
                new BigDecimal("25.50"),
                ProductStatus.published,
                new ProductTrackingDto(true, true, true, false),
                new ProductChannelsDto(true, true, false));
    }
}
