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
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.UnitStatus;
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
    void duplicateBarcodeInSameTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(productRepository.existsByTenantIdAndBarcode(TENANT_ID, request.barcode())).willReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("PRODUCT_BARCODE_CONFLICT");
                });
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void blankBarcodeIsPersistedAsNull() {
        allowValidCreation();

        service.create(requestWithIdentity("SKU-001", "   "));

        assertThat(savedProduct().getBarcode()).isNull();
    }

    @Test
    void skuIsTrimmedBeforeUniquenessCheckAndPersistence() {
        allowValidCreation();

        service.create(requestWithIdentity("  SKU-001  ", null));

        verify(productRepository).existsByTenantIdAndSku(TENANT_ID, "SKU-001");
        assertThat(savedProduct().getSku()).isEqualTo("SKU-001");
    }

    @Test
    void categoryFromAnotherTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "CATEGORY_NOT_FOUND");
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingCategoryIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "CATEGORY_NOT_FOUND");
    }

    @Test
    void archivedCategoryIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "CATEGORY_NOT_FOUND");
    }

    @Test
    void baseUnitFromAnotherTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.baseUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "UNIT_NOT_FOUND");
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingUnitIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.baseUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "UNIT_NOT_FOUND");
    }

    @Test
    void archivedUnitIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.baseUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "UNIT_NOT_FOUND");
    }

    @Test
    void optionalInventoryUnitFromAnotherTenantIsRejected() {
        ProductCreateRequest request = validRequest();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.baseUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.inventoryUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "UNIT_NOT_FOUND");
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void saleUnitFromAnotherTenantIsRejected() {
        ProductCreateRequest request = requestWithInventoryUnit(null);
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        request.categoryId(), TENANT_ID, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.baseUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        request.saleUnitId(), TENANT_ID, UnitStatus.active))
                .willReturn(false);

        assertNotFound(() -> service.create(request), "UNIT_NOT_FOUND");
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void activeReferencesAndNullOptionalUnitsAreAccepted() {
        allowValidCreation();

        service.create(requestWithUnits(null, null));

        Product saved = savedProduct();
        assertThat(saved.getInventoryUnitId()).isNull();
        assertThat(saved.getSaleUnitId()).isNull();
    }

    @Test
    void activeCategoryAndUnitsAreAccepted() {
        allowValidCreation();

        service.create(validRequest());

        verify(productRepository).saveAndFlush(any(Product.class));
    }

    @Test
    void createdEntityReceivesAuthenticatedTenantId() {
        allowValidCreation();

        ProductDto response = service.create(validRequest());

        assertThat(savedProduct().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(response.tenantId()).isEqualTo(TENANT_ID);
    }

    private void allowValidCreation() {
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                        any(UUID.class), any(UUID.class), any(CategoryStatus.class)))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                        any(UUID.class), any(UUID.class), any(UnitStatus.class)))
                .willReturn(true);
        given(productRepository.saveAndFlush(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    private Product savedProduct() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static void assertNotFound(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }

    private static ProductCreateRequest validRequest() {
        return requestWithInventoryUnit(UUID.randomUUID());
    }

    private static ProductCreateRequest requestWithInventoryUnit(UUID inventoryUnitId) {
        return requestWithUnits(inventoryUnitId, UUID.randomUUID());
    }

    private static ProductCreateRequest requestWithUnits(UUID inventoryUnitId, UUID saleUnitId) {
        return request("SKU-001", "123456789", inventoryUnitId, saleUnitId);
    }

    private static ProductCreateRequest requestWithIdentity(String sku, String barcode) {
        return request(sku, barcode, UUID.randomUUID(), UUID.randomUUID());
    }

    private static ProductCreateRequest request(
            String sku, String barcode, UUID inventoryUnitId, UUID saleUnitId) {
        return new ProductCreateRequest(
                sku,
                barcode,
                "Producto",
                "Descripcion",
                "Marca",
                ProductType.physical,
                UUID.randomUUID(),
                UUID.randomUUID(),
                inventoryUnitId,
                saleUnitId,
                new BigDecimal("25.50"),
                ProductStatus.published,
                new ProductTrackingDto(true, true, false, false),
                new ProductChannelsDto(true, true, false));
    }
}
