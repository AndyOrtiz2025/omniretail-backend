package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.dto.ProductUpdateRequest;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.UnitStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.ProductMediaRepository;
import com.omniretail.backend.catalog.repository.ProductKitComponentRepository;
import com.omniretail.backend.catalog.repository.ProductPriceHistoryRepository;
import com.omniretail.backend.catalog.repository.UnitConversionRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.inventory.repository.InventoryBalanceRepository;
import com.omniretail.backend.inventory.repository.InventoryMovementRepository;
import com.omniretail.backend.pos.repository.SaleItemRepository;
import com.omniretail.backend.purchasing.repository.PurchaseOrderItemRepository;
import com.omniretail.backend.purchasing.repository.SupplierProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductManagementServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID BASE_UNIT_ID = UUID.randomUUID();

    @Mock private ProductRepository productRepository;
    @Mock private ProductMediaRepository productMediaRepository;
    @Mock private ProductPriceHistoryRepository productPriceHistoryRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UnitRepository unitRepository;
    @Mock private UnitConversionRepository unitConversionRepository;
    @Mock private InventoryBalanceRepository inventoryBalanceRepository;
    @Mock private InventoryMovementRepository inventoryMovementRepository;
    @Mock private SupplierProductRepository supplierProductRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private ProductKitComponentRepository productKitComponentRepository;
    @Mock private ProductKitService productKitService;
    @Mock private BusinessConfigService businessConfigService;
    @Mock private TenantCapabilityGuard tenantCapabilityGuard;
    @Mock private CurrentUser currentUser;

    private ProductService service;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new ProductService(
                productRepository,
                productMediaRepository,
                productPriceHistoryRepository,
                categoryRepository,
                unitRepository,
                unitConversionRepository,
                inventoryBalanceRepository,
                inventoryMovementRepository,
                supplierProductRepository,
                purchaseOrderItemRepository,
                saleItemRepository,
                productKitComponentRepository,
                productKitService,
                businessConfigService,
                tenantCapabilityGuard,
                currentUser);
        product = product();
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                UUID.randomUUID(), TENANT, UserType.employee, null, null, UUID.randomUUID()));
        lenient().when(productRepository.findByTenantIdAndId(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(product));
        lenient().when(productRepository.findForUpdateByTenantIdAndId(TENANT, PRODUCT_ID))
                .thenReturn(Optional.of(product));
        lenient().when(productRepository.saveAndFlush(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(businessConfigService.getConfig()).thenReturn(fullConfig());
    }

    @Test
    void detailReturnsPublishedAndArchivedButHidesCrossTenantOrMissing() {
        assertThat(service.get(PRODUCT_ID).id()).isEqualTo(PRODUCT_ID);
        product.setStatus(ProductStatus.archived);
        assertThat(service.get(PRODUCT_ID).status()).isEqualTo(ProductStatus.archived);

        UUID missing = UUID.randomUUID();
        given(productRepository.findByTenantIdAndId(TENANT, missing)).willReturn(Optional.empty());
        assertCode(() -> service.get(missing), "PRODUCT_NOT_FOUND");
        verify(productRepository).findByTenantIdAndId(TENANT, missing);
    }

    @Test
    void updateAndArchiveHideProductsOutsideTheAuthenticatedTenant() {
        UUID foreignProduct = UUID.randomUUID();
        given(productRepository.findForUpdateByTenantIdAndId(TENANT, foreignProduct))
                .willReturn(Optional.empty());

        assertCode(() -> service.update(foreignProduct, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))),
                "PRODUCT_NOT_FOUND");
        assertCode(() -> service.archive(foreignProduct), "PRODUCT_NOT_FOUND");
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void updateReplacesEditableFieldsNormalizesIdentityAndPreservesPriceAndStatus() {
        ProductUpdateRequest request = request(
                "  new sku  ", "  ", ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false));

        ProductDto response = service.update(PRODUCT_ID, request);

        assertThat(response.sku()).isEqualTo("NEW-SKU");
        assertThat(response.barcode()).isNull();
        assertThat(response.name()).isEqualTo("Nombre nuevo");
        assertThat(response.channels()).isEqualTo(new ProductChannelsDto(false, true, true));
        assertThat(response.salePrice()).isEqualByComparingTo("50.00");
        assertThat(response.status()).isEqualTo(ProductStatus.published);
        verify(productRepository).existsByTenantIdAndSkuAndIdNot(TENANT, "NEW-SKU", PRODUCT_ID);
        assertThat(ProductUpdateRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("id", "tenantId", "createdAt", "updatedAt", "status", "salePrice");
    }

    @Test
    void ownSkuAndBarcodeAreAllowedWhileDuplicatesAreRejected() {
        ProductUpdateRequest own = request(
                "sku-old", "BAR-OLD", ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false));
        service.update(PRODUCT_ID, own);

        given(productRepository.existsByTenantIdAndSkuAndIdNot(TENANT, "DUP", PRODUCT_ID))
                .willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "dup", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_SKU_CONFLICT");

        given(productRepository.existsByTenantIdAndBarcodeAndIdNot(TENANT, "DUP-B", PRODUCT_ID))
                .willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "other", "DUP-B", ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_BARCODE_CONFLICT");
    }

    @Test
    void changedReferencesMustBeActiveButUnchangedArchivedReferencesArePreserved() {
        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false)));
        verify(categoryRepository, never()).existsByIdAndTenantIdAndStatus(any(), any(), any());
        verify(unitRepository, never()).existsByIdAndTenantIdAndStatus(any(), any(), any());

        UUID newCategory = UUID.randomUUID();
        UUID newUnit = UUID.randomUUID();
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                newCategory, TENANT, CategoryStatus.active)).willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                newUnit, TENANT, UnitStatus.active)).willReturn(true);
        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, newCategory, BASE_UNIT_ID,
                newUnit, newUnit, tracking(false, false, false, false)));
        assertThat(product.getCategoryId()).isEqualTo(newCategory);

        UUID missingCategory = UUID.randomUUID();
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, missingCategory, BASE_UNIT_ID,
                newUnit, newUnit, tracking(false, false, false, false))), "CATEGORY_NOT_FOUND");

        UUID missingUnit = UUID.randomUUID();
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, newCategory, BASE_UNIT_ID,
                missingUnit, newUnit, tracking(false, false, false, false))), "UNIT_NOT_FOUND");

        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, newCategory, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false)));
        assertThat(product.getInventoryUnitId()).isNull();
        assertThat(product.getSaleUnitId()).isNull();
    }

    @Test
    void pristineBaseUnitCanChangeButAnyStructuralHistoryBlocksIt() {
        UUID newBase = UUID.randomUUID();
        given(unitRepository.existsByIdAndTenantIdAndStatus(newBase, TENANT, UnitStatus.active))
                .willReturn(true);
        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, newBase,
                null, null, tracking(false, false, false, false)));
        assertThat(product.getBaseUnitId()).isEqualTo(newBase);

        product.setBaseUnitId(BASE_UNIT_ID);
        given(inventoryMovementRepository.existsByTenantIdAndProductId(TENANT, PRODUCT_ID))
                .willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, newBase,
                null, null, tracking(false, false, false, false))),
                "PRODUCT_BASE_UNIT_CHANGE_NOT_ALLOWED");
    }

    @Test
    void unchangedBaseUnitAndProductTypeDoNotApplyStructuralRestrictions() {
        ProductDto response = service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false)));

        assertThat(response.name()).isEqualTo("Nombre nuevo");
        verify(inventoryMovementRepository, never())
                .existsByTenantIdAndProductId(TENANT, PRODUCT_ID);
        org.mockito.Mockito.verifyNoInteractions(
                inventoryBalanceRepository,
                supplierProductRepository,
                purchaseOrderItemRepository,
                saleItemRepository,
                unitConversionRepository);
    }

    @Test
    void eachCriticalHistorySourceParticipatesInStructuralGuard() {
        assertStructuralBlock(() -> given(inventoryBalanceRepository
                .existsPositiveStockByTenantIdAndProductId(TENANT, PRODUCT_ID)).willReturn(true));
        assertStructuralBlock(() -> given(supplierProductRepository
                .existsByTenantIdAndProductId(TENANT, PRODUCT_ID)).willReturn(true));
        assertStructuralBlock(() -> given(purchaseOrderItemRepository
                .existsByTenantIdAndProductId(TENANT, PRODUCT_ID)).willReturn(true));
        assertStructuralBlock(() -> given(saleItemRepository
                .existsByTenantIdAndProductId(TENANT, PRODUCT_ID)).willReturn(true));
        assertStructuralBlock(() -> given(unitConversionRepository
                .existsByTenantIdAndProductId(TENANT, PRODUCT_ID)).willReturn(true));
    }

    @Test
    void pristinePhysicalCanBecomeServiceAndServiceAlwaysDisablesTracking() {
        product.setTrackingStock(true);
        ProductDto response = service.update(PRODUCT_ID, request(
                "sku", null, ProductType.service, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(true, true, true, true)));

        assertThat(response.productType()).isEqualTo(ProductType.service);
        assertThat(response.tracking()).isEqualTo(tracking(false, false, false, false));
    }

    @Test
    void serviceRequiresBusinessCapabilityOnCreateAndTransition() {
        given(businessConfigService.getConfig()).willReturn(disabledConfig());
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.service, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))),
                "PRODUCT_CAPABILITY_DISABLED");

        allowCreateReferences();
        assertCode(() -> service.create(createRequest(
                ProductType.service, tracking(false, false, false, false))),
                "PRODUCT_CAPABILITY_DISABLED");
    }

    @Test
    void saleHistoryBlocksProductTypeReinterpretation() {
        given(saleItemRepository.existsByTenantIdAndProductId(TENANT, PRODUCT_ID))
                .willReturn(true);

        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.service, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))),
                "PRODUCT_TYPE_CHANGE_NOT_ALLOWED");
    }

    @Test
    void kitTransitionAndArchivedUpdateAreRejected() {
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.kit, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_KIT_NOT_SUPPORTED");
        product.setProductType(ProductType.kit);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_KIT_NOT_SUPPORTED");
        product.setStatus(ProductStatus.archived);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_ARCHIVED");
    }

    @Test
    void legacyKitCannotNewlyEnableStockTracking() {
        product.setProductType(ProductType.kit);

        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.kit, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(true, false, false, false))),
                "PRODUCT_CAPABILITY_DISABLED");
    }

    @Test
    void enablingTraceabilityChecksEntitlementButPreservingItAfterLossDoesNot() {
        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, true, false, false)));
        verify(tenantCapabilityGuard).ensureTenantCapability(TENANT, SaasCapability.traceabilityLots);

        org.mockito.Mockito.reset(tenantCapabilityGuard);
        product.setTrackingLot(true);
        given(businessConfigService.getConfig()).willReturn(disabledConfig());
        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, true, false, false)));
        verify(tenantCapabilityGuard, never()).ensureTenantCapability(any(), any());
    }

    @Test
    void missingSaasEntitlementRejectsNewTraceabilityFlag() {
        org.mockito.Mockito.doThrow(new BusinessException(
                        HttpStatus.FORBIDDEN,
                        "CAPABILITY_REQUIRED",
                        "Capacidad no disponible."))
                .when(tenantCapabilityGuard)
                .ensureTenantCapability(TENANT, SaasCapability.traceabilityLots);

        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, true, false, false))),
                "CAPABILITY_REQUIRED");
    }

    @Test
    void disabledPackagingAllowsPreservedConfigurationButRejectsNewAlternatives() {
        UUID historicalAlternative = UUID.randomUUID();
        product.setInventoryUnitId(historicalAlternative);
        given(businessConfigService.getConfig()).willReturn(disabledConfig());

        service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                historicalAlternative, null, tracking(false, false, false, false)));

        UUID newAlternative = UUID.randomUUID();
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                newAlternative, TENANT, UnitStatus.active)).willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                newAlternative, null, tracking(false, false, false, false))),
                "PRODUCT_CAPABILITY_DISABLED");

        UUID newBase = UUID.randomUUID();
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                newBase, TENANT, UnitStatus.active)).willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, newBase,
                historicalAlternative, null, tracking(false, false, false, false))),
                "PRODUCT_CAPABILITY_DISABLED");
    }

    @Test
    void disabledPackagingRejectsAlternativeUnitsOnCreate() {
        allowCreateReferences();
        given(businessConfigService.getConfig()).willReturn(disabledConfig());

        assertCode(() -> service.create(createRequest(
                ProductType.physical,
                tracking(false, false, false, false),
                UUID.randomUUID(),
                null)),
                "PRODUCT_CAPABILITY_DISABLED");
    }

    @Test
    void trackingChangeWithOperationalHistoryIsRejectedAndExpirationRequiresLot() {
        given(saleItemRepository.existsByTenantIdAndProductId(TENANT, PRODUCT_ID)).willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(true, false, false, false))),
                "PRODUCT_TRACKING_CHANGE_NOT_ALLOWED");

        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, true, false))), "PRODUCT_TRACKING_INVALID");
    }

    @Test
    void supplierProductHistoryBlocksTrackingStockTransition() {
        given(supplierProductRepository.existsByTenantIdAndProductId(TENANT, PRODUCT_ID))
                .willReturn(true);

        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(true, false, false, false))),
                "PRODUCT_TRACKING_CHANGE_NOT_ALLOWED");
    }

    @Test
    void archiveIsSoftIdempotentAndBlocksStockOrOpenPurchaseOrders() {
        service.archive(PRODUCT_ID);
        assertThat(product.getStatus()).isEqualTo(ProductStatus.archived);
        verify(productRepository).saveAndFlush(product);
        service.archive(PRODUCT_ID);
        verify(productRepository, org.mockito.Mockito.times(1)).saveAndFlush(product);

        product.setStatus(ProductStatus.published);
        given(inventoryBalanceRepository.existsPositiveStockByTenantIdAndProductId(TENANT, PRODUCT_ID))
                .willReturn(true);
        assertCode(() -> service.archive(PRODUCT_ID), "PRODUCT_ARCHIVE_HAS_STOCK");
        given(inventoryBalanceRepository.existsPositiveStockByTenantIdAndProductId(TENANT, PRODUCT_ID))
                .willReturn(false);
        given(purchaseOrderItemRepository.existsOpenOrderForProduct(TENANT, PRODUCT_ID)).willReturn(true);
        assertCode(() -> service.archive(PRODUCT_ID), "PRODUCT_ARCHIVE_HAS_OPEN_PO");
    }

    @Test
    void supplierSalesAndConversionsAloneDoNotBlockArchive() {
        service.archive(PRODUCT_ID);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.archived);
        verify(supplierProductRepository, never()).existsByTenantIdAndProductId(any(), any());
        verify(saleItemRepository, never()).existsByTenantIdAndProductId(any(), any());
        verify(unitConversionRepository, never()).existsByTenantIdAndProductId(any(), any());
    }

    @Test
    void archiveRejectsComponentReferencedByPublishedKit() {
        given(productKitComponentRepository.existsInPublishedKit(TENANT, PRODUCT_ID)).willReturn(true);
        assertCode(() -> service.archive(PRODUCT_ID), "KIT_COMPONENT_IN_USE");
        assertThat(product.getStatus()).isEqualTo(ProductStatus.published);
    }

    @Test
    void restoreValidatesReferencesIsIdempotentAndHidesCrossTenantProducts() {
        product.setStatus(ProductStatus.archived);
        given(categoryRepository.existsByIdAndTenantIdAndStatus(CATEGORY_ID, TENANT, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(BASE_UNIT_ID, TENANT, UnitStatus.active))
                .willReturn(true);

        ProductDto restored = service.restore(PRODUCT_ID);

        assertThat(restored.status()).isEqualTo(ProductStatus.published);
        verify(productRepository).saveAndFlush(product);
        service.restore(PRODUCT_ID);
        verify(productRepository, org.mockito.Mockito.times(1)).saveAndFlush(product);

        UUID foreignId = UUID.randomUUID();
        assertCode(() -> service.restore(foreignId), "PRODUCT_NOT_FOUND");
    }

    @Test
    void restoreRejectsInactiveCatalogReferencesAndInvalidKitComponents() {
        product.setStatus(ProductStatus.archived);
        assertCode(() -> service.restore(PRODUCT_ID), "CATEGORY_NOT_FOUND");

        given(categoryRepository.existsByIdAndTenantIdAndStatus(CATEGORY_ID, TENANT, CategoryStatus.active))
                .willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(BASE_UNIT_ID, TENANT, UnitStatus.active))
                .willReturn(true);
        product.setProductType(ProductType.kit);
        org.mockito.Mockito.doThrow(BusinessException.conflict("KIT_COMPONENTS_REQUIRED", "Faltan componentes."))
                .when(productKitService).validatePublishable(TENANT, product);
        assertCode(() -> service.restore(PRODUCT_ID), "KIT_COMPONENTS_REQUIRED");
    }

    @Test
    void expectedDatabaseUniqueRacesTranslateAndUnrelatedIntegrityErrorsPropagate() {
        given(productRepository.saveAndFlush(any(Product.class)))
                .willThrow(new DataIntegrityViolationException("constraint uk_products_tenant_sku"));
        assertCode(() -> service.update(PRODUCT_ID, request(
                "race", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_SKU_CONFLICT");

        given(productRepository.saveAndFlush(any(Product.class)))
                .willThrow(new DataIntegrityViolationException("constraint uk_products_tenant_barcode"));
        assertCode(() -> service.update(PRODUCT_ID, request(
                "race2", "BAR", ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))), "PRODUCT_BARCODE_CONFLICT");

        given(productRepository.saveAndFlush(any(Product.class)))
                .willThrow(new DataIntegrityViolationException("unrelated foreign key"));
        assertThatThrownBy(() -> service.update(PRODUCT_ID, request(
                "race3", null, ProductType.physical, CATEGORY_ID, BASE_UNIT_ID,
                null, null, tracking(false, false, false, false))))
                .isExactlyInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publishedKitCreationRequiresComponentConfigurationAndServiceTrackingIsForcedOff() {
        allowCreateReferences();
        assertCode(() -> service.create(createRequest(
                ProductType.kit, tracking(false, false, false, false))),
                "KIT_COMPONENTS_REQUIRED");

        ProductDto serviceProduct = service.create(createRequest(
                ProductType.service, tracking(true, true, true, true)));
        assertThat(serviceProduct.tracking()).isEqualTo(tracking(false, false, false, false));
    }

    @Test
    void createsArchivedKitWithoutStockSoComponentsCanBeConfiguredBeforeRestore() {
        allowCreateReferences();
        ProductCreateRequest request = new ProductCreateRequest("KIT-DRAFT", null, "Kit", null, null,
                ProductType.kit, CATEGORY_ID, BASE_UNIT_ID, null, null, BigDecimal.TEN,
                ProductStatus.archived, tracking(true, true, true, true),
                new ProductChannelsDto(true, true, false));

        ProductDto created = service.create(request);

        assertThat(created.productType()).isEqualTo(ProductType.kit);
        assertThat(created.status()).isEqualTo(ProductStatus.archived);
        assertThat(created.tracking()).isEqualTo(tracking(false, false, false, false));
        verify(tenantCapabilityGuard).ensureTenantCapability(TENANT, SaasCapability.catalogKits);
    }

    @Test
    void createEnforcesBusinessCapabilitiesAndTraceabilityEntitlements() {
        allowCreateReferences();
        given(businessConfigService.getConfig()).willReturn(new BusinessConfigResponse(
                TENANT, BusinessPreset.custom,
                false, false, false, false, false, true, true, false, true,
                List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(
                        false, false, false, false)));
        assertCode(() -> service.create(createRequest(
                ProductType.physical, tracking(true, false, false, false))),
                "PRODUCT_CAPABILITY_DISABLED");

        given(businessConfigService.getConfig()).willReturn(fullConfig());
        service.create(createRequest(ProductType.physical, tracking(true, true, true, true)));
        verify(tenantCapabilityGuard).ensureTenantCapability(TENANT, SaasCapability.traceabilityLots);
        verify(tenantCapabilityGuard).ensureTenantCapability(TENANT, SaasCapability.traceabilityExpiration);
        verify(tenantCapabilityGuard).ensureTenantCapability(TENANT, SaasCapability.traceabilitySerials);
    }

    private void assertStructuralBlock(Runnable stub) {
        org.mockito.Mockito.reset(
                inventoryBalanceRepository,
                inventoryMovementRepository,
                supplierProductRepository,
                purchaseOrderItemRepository,
                saleItemRepository,
                unitConversionRepository);
        stub.run();
        UUID newBase = UUID.randomUUID();
        given(unitRepository.existsByIdAndTenantIdAndStatus(newBase, TENANT, UnitStatus.active))
                .willReturn(true);
        assertCode(() -> service.update(PRODUCT_ID, request(
                "sku", null, ProductType.physical, CATEGORY_ID, newBase,
                null, null, tracking(false, false, false, false))),
                "PRODUCT_BASE_UNIT_CHANGE_NOT_ALLOWED");
    }

    private static Product product() {
        Product value = Product.builder()
                .sku("SKU-OLD")
                .barcode("BAR-OLD")
                .name("Anterior")
                .productType(ProductType.physical)
                .categoryId(CATEGORY_ID)
                .baseUnitId(BASE_UNIT_ID)
                .salePrice(new BigDecimal("50.00"))
                .status(ProductStatus.published)
                .trackingStock(false)
                .trackingLot(false)
                .trackingExpiration(false)
                .trackingSerial(false)
                .channelEcommerce(true)
                .channelPos(true)
                .channelMobileApp(false)
                .build();
        value.setTenantId(TENANT);
        ReflectionTestUtils.setField(value, "id", PRODUCT_ID);
        return value;
    }

    private void allowCreateReferences() {
        given(categoryRepository.existsByIdAndTenantIdAndStatus(
                any(), any(), any())).willReturn(true);
        given(unitRepository.existsByIdAndTenantIdAndStatus(
                any(), any(), any())).willReturn(true);
    }

    private static ProductCreateRequest createRequest(
            ProductType type, ProductTrackingDto tracking) {
        return createRequest(type, tracking, null, null);
    }

    private static ProductCreateRequest createRequest(
            ProductType type,
            ProductTrackingDto tracking,
            UUID inventoryUnitId,
            UUID saleUnitId) {
        return new ProductCreateRequest(
                "SKU-CREATE-" + UUID.randomUUID(),
                null,
                "Creado",
                null,
                null,
                type,
                CATEGORY_ID,
                BASE_UNIT_ID,
                inventoryUnitId,
                saleUnitId,
                BigDecimal.TEN,
                ProductStatus.published,
                tracking,
                new ProductChannelsDto(true, true, false));
    }

    private static ProductUpdateRequest request(
            String sku,
            String barcode,
            ProductType type,
            UUID category,
            UUID baseUnit,
            UUID inventoryUnit,
            UUID saleUnit,
            ProductTrackingDto tracking) {
        return new ProductUpdateRequest(
                sku, barcode, "Nombre nuevo", "Descripción", "Marca",
                type, category, baseUnit, inventoryUnit, saleUnit, tracking,
                new ProductChannelsDto(false, true, true));
    }

    private static ProductTrackingDto tracking(
            boolean stock, boolean lot, boolean expiration, boolean serial) {
        return new ProductTrackingDto(stock, lot, expiration, serial);
    }

    private static BusinessConfigResponse fullConfig() {
        return new BusinessConfigResponse(
                TENANT, BusinessPreset.custom,
                true, true, true, true, true, true, true, true, true,
                List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(
                        true, true, true, true));
    }

    private static BusinessConfigResponse disabledConfig() {
        return new BusinessConfigResponse(
                TENANT, BusinessPreset.custom,
                false, false, false, false, false, false, false, false, false,
                List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(
                        false, false, false, false));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
