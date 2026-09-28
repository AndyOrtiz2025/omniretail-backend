package com.omniretail.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class UnitConversionControllerTest {

    private static final String CONVERSIONS = "/api/v1/catalog/unit-conversions";
    private static final String READ_PERMISSION = "catalog.units.read";
    private static final String MANAGE_PERMISSION = "catalog.units.manage";
    private static final String CAPABILITY_ERROR = "BUSINESS_CAPABILITY_DISABLED";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private PermissionResolver permissionResolver;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), anyString()))
                .willReturn(true);
    }

    @Test
    void listWithoutAuthenticationReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(CONVERSIONS)).andExpect(status().isUnauthorized());
    }

    @Test
    void detailWithoutReadPermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyReadPermission();

        mockMvc.perform(get(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void postWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                null, UUID.randomUUID(), UUID.randomUUID(), "2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void putWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(put(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void deleteWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(delete(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void listReturnsForbiddenWhenCapabilityIsDisabled() throws Exception {
        UUID tenantId = insertTenant(false);

        mockMvc.perform(get(CONVERSIONS).header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR))
                .andExpect(jsonPath("$.message")
                        .value("La gestión de unidades y empaques no está habilitada para este negocio."));
    }

    @Test
    void detailReturnsForbiddenWhenCapabilityIsDisabled() throws Exception {
        UUID tenantId = insertTenant(false);

        mockMvc.perform(get(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void createReturnsForbiddenWhenCapabilityIsDisabled() throws Exception {
        UUID tenantId = insertTenant(false);

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                null, UUID.randomUUID(), UUID.randomUUID(), "2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void updateReturnsForbiddenWhenCapabilityIsDisabled() throws Exception {
        UUID tenantId = insertTenant(false);

        mockMvc.perform(put(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void deleteReturnsForbiddenWhenCapabilityIsDisabled() throws Exception {
        UUID tenantId = insertTenant(false);

        mockMvc.perform(delete(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void enabledCapabilityAllowsAccess() throws Exception {
        UUID tenantId = insertTenant(true);

        mockMvc.perform(get(CONVERSIONS).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void listIsTenantScopedAndReturnsPageResponse() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromA = insertUnit(tenantA, "active", "unit");
        UUID toA = insertUnit(tenantA, "active", "unit");
        UUID fromB = insertUnit(tenantB, "active", "unit");
        UUID toB = insertUnit(tenantB, "active", "unit");
        UUID expected = insertConversion(tenantA, null, fromA, toA, "24");
        insertConversion(tenantB, null, fromB, toB, "50");

        mockMvc.perform(get(CONVERSIONS).header("Authorization", token(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.items[0].tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void detailFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromB = insertUnit(tenantB, "active", "unit");
        UUID toB = insertUnit(tenantB, "active", "unit");
        UUID conversionB = insertConversion(tenantB, null, fromB, toB, "2");

        mockMvc.perform(get(CONVERSIONS + "/" + conversionB)
                        .header("Authorization", token(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_NOT_FOUND"));
    }

    @Test
    void detailNonexistentConversionReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);

        mockMvc.perform(get(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Conversión de unidad no encontrada."));
    }

    @Test
    void updateFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromB = insertUnit(tenantB, "active", "unit");
        UUID toB = insertUnit(tenantB, "active", "unit");
        UUID conversionB = insertConversion(tenantB, null, fromB, toB, "2");

        mockMvc.perform(put(CONVERSIONS + "/" + conversionB)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("3")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_NOT_FOUND"));
    }

    @Test
    void deleteFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromB = insertUnit(tenantB, "active", "unit");
        UUID toB = insertUnit(tenantB, "active", "unit");
        UUID conversionB = insertConversion(tenantB, null, fromB, toB, "2");

        mockMvc.perform(delete(CONVERSIONS + "/" + conversionB)
                        .header("Authorization", token(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_NOT_FOUND"));
    }

    @Test
    void createWithFromUnitFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromB = insertUnit(tenantB, "active", "unit");
        UUID toA = insertUnit(tenantA, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromB, toA, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_FROM_UNIT_NOT_FOUND"));
    }

    @Test
    void createWithToUnitFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromA = insertUnit(tenantA, "active", "unit");
        UUID toB = insertUnit(tenantB, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromA, toB, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_TO_UNIT_NOT_FOUND"));
    }

    @Test
    void createWithProductFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant(true);
        UUID tenantB = insertTenant(true);
        UUID fromA = insertUnit(tenantA, "active", "unit");
        UUID toA = insertUnit(tenantA, "active", "unit");
        UUID baseUnitB = insertUnit(tenantB, "active", "unit");
        UUID productB = insertProduct(tenantB, baseUnitB, "published");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(productB, fromA, toA, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_PRODUCT_NOT_FOUND"));
    }

    @Test
    void createGlobalConversionPersistsNullProductAndCompleteResponse() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, "24.500000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(7)))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.productId").value(nullValue()))
                .andExpect(jsonPath("$.fromUnitId").value(fromUnitId.toString()))
                .andExpect(jsonPath("$.toUnitId").value(toUnitId.toString()))
                .andExpect(jsonPath("$.factor").value(24.5))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        UUID conversionId = globalConversionId(tenantId, fromUnitId, toUnitId);
        assertThat(conversionProductId(conversionId)).isNull();
        assertThat(conversionFactor(conversionId)).isEqualByComparingTo("24.500000");
    }

    @Test
    void createProductSpecificConversionPersistsProduct() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "published");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(productId, fromUnitId, toUnitId, "12")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.factor").value(12));
    }

    @Test
    void samePairForDifferentProductsIsAllowed() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productA = insertProduct(tenantId, toUnitId, "published");
        UUID productB = insertProduct(tenantId, toUnitId, "published");
        insertConversion(tenantId, productA, fromUnitId, toUnitId, "12");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(productB, fromUnitId, toUnitId, "24")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productB.toString()));
    }

    @Test
    void globalAndProductSpecificSamePairAreAllowed() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "published");
        insertConversion(tenantId, null, fromUnitId, toUnitId, "12");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(productId, fromUnitId, toUnitId, "24")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productId.toString()));
    }

    @Test
    void archivedProductIsAllowedForProductSpecificConversion() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "archived");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(productId, fromUnitId, toUnitId, "24")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productId.toString()));
    }

    @Test
    void conversionAcrossDifferentUnitCategoriesIsAllowed() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "weight");
        UUID toUnitId = insertUnit(tenantId, "active", "volume");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, "1.25")))
                .andExpect(status().isCreated());
    }

    @Test
    void reverseDirectedConversionIsAllowed() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID unitA = insertUnit(tenantId, "active", "unit");
        UUID unitB = insertUnit(tenantId, "active", "unit");
        insertConversion(tenantId, null, unitA, unitB, "24");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, unitB, unitA, "0.041667")))
                .andExpect(status().isCreated());
    }

    @Test
    void createDoesNotGenerateInverseConversion() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, "24")))
                .andExpect(status().isCreated());

        assertThat(conversionCount(tenantId, fromUnitId, toUnitId)).isEqualTo(1);
        assertThat(conversionCount(tenantId, toUnitId, fromUnitId)).isZero();
    }

    @Test
    void createRejectsSameFromAndToUnit() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID unitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, unitId, unitId, "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_SAME_UNIT"))
                .andExpect(jsonPath("$.message")
                        .value("Las unidades origen y destino no pueden ser iguales."));
    }

    @Test
    void createRejectsZeroFactor() throws Exception {
        assertInvalidCreateFactor("0");
    }

    @Test
    void createRejectsNegativeFactor() throws Exception {
        assertInvalidCreateFactor("-1");
    }

    @Test
    void createRejectsFactorWithMoreThanSixDecimals() throws Exception {
        assertInvalidCreateFactor("1.1234567");
    }

    @Test
    void createRejectsFactorWithMoreThanTwelveIntegerDigits() throws Exception {
        assertInvalidCreateFactor("1234567890123");
    }

    @Test
    void createWithNonexistentFromUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID toUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, UUID.randomUUID(), toUnitId, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_FROM_UNIT_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Unidad origen no encontrada o inactiva."));
    }

    @Test
    void createWithArchivedFromUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "archived", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_FROM_UNIT_NOT_FOUND"));
    }

    @Test
    void createWithNonexistentToUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, UUID.randomUUID(), "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_TO_UNIT_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Unidad destino no encontrada o inactiva."));
    }

    @Test
    void createWithArchivedToUnitReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "archived", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_TO_UNIT_NOT_FOUND"));
    }

    @Test
    void createWithNonexistentProductReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                UUID.randomUUID(), fromUnitId, toUnitId, "2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("UNIT_CONVERSION_PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Producto no encontrado."));
    }

    @Test
    void duplicateGlobalConversionReturnsConflict() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        insertConversion(tenantId, null, fromUnitId, toUnitId, "2");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, "3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Ya existe una conversión para este par de unidades."));
    }

    @Test
    void duplicateProductSpecificConversionReturnsConflict() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "published");
        insertConversion(tenantId, productId, fromUnitId, toUnitId, "2");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(productId, fromUnitId, toUnitId, "3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_CONFLICT"));
    }

    @Test
    void updateChangesOnlyFactorAndKeepsTopology() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "published");
        UUID conversionId =
                insertConversion(tenantId, productId, fromUnitId, toUnitId, "12");

        mockMvc.perform(put(CONVERSIONS + "/" + conversionId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("24.500000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversionId.toString()))
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.fromUnitId").value(fromUnitId.toString()))
                .andExpect(jsonPath("$.toUnitId").value(toUnitId.toString()))
                .andExpect(jsonPath("$.factor").value(24.5));

        assertThat(conversionFactor(conversionId)).isEqualByComparingTo("24.500000");
        assertThat(conversionProductId(conversionId)).isEqualTo(productId);
        assertThat(conversionUnitId(conversionId, "from_unit_id")).isEqualTo(fromUnitId);
        assertThat(conversionUnitId(conversionId, "to_unit_id")).isEqualTo(toUnitId);
    }

    @Test
    void updateNonexistentConversionReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);

        mockMvc.perform(put(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_NOT_FOUND"));
    }

    @Test
    void updateRejectsInvalidFactor() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID conversionId = insertConversion(tenantId, null, fromUnitId, toUnitId, "2");

        mockMvc.perform(put(CONVERSIONS + "/" + conversionId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateFactorIsAllowedAfterUnitIsArchived() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID conversionId = insertConversion(tenantId, null, fromUnitId, toUnitId, "2");
        archiveUnit(fromUnitId);

        mockMvc.perform(put(CONVERSIONS + "/" + conversionId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factor").value(3));
    }

    @Test
    void deleteReturnsNoContentAndRemovesRow() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID conversionId = insertConversion(tenantId, null, fromUnitId, toUnitId, "2");

        mockMvc.perform(delete(CONVERSIONS + "/" + conversionId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        assertThat(conversionExists(conversionId)).isFalse();
    }

    @Test
    void deleteNonexistentConversionReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant(true);

        mockMvc.perform(delete(CONVERSIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_CONVERSION_NOT_FOUND"));
    }

    @Test
    void listFiltersByProductId() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productA = insertProduct(tenantId, toUnitId, "published");
        UUID productB = insertProduct(tenantId, toUnitId, "published");
        UUID expected = insertConversion(tenantId, productA, fromUnitId, toUnitId, "2");
        insertConversion(tenantId, productB, fromUnitId, toUnitId, "3");

        mockMvc.perform(get(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .param("productId", productA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listFiltersByFromUnitId() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromA = insertUnit(tenantId, "active", "unit");
        UUID fromB = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID expected = insertConversion(tenantId, null, fromA, toUnitId, "2");
        insertConversion(tenantId, null, fromB, toUnitId, "3");

        mockMvc.perform(get(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .param("fromUnitId", fromA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listFiltersByToUnitId() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toA = insertUnit(tenantId, "active", "unit");
        UUID toB = insertUnit(tenantId, "active", "unit");
        UUID expected = insertConversion(tenantId, null, fromUnitId, toA, "2");
        insertConversion(tenantId, null, fromUnitId, toB, "3");

        mockMvc.perform(get(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .param("toUnitId", toA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listAppliesCombinedFilters() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID otherFrom = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID otherTo = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "published");
        UUID otherProduct = insertProduct(tenantId, toUnitId, "published");
        UUID expected =
                insertConversion(tenantId, productId, fromUnitId, toUnitId, "2");
        insertConversion(tenantId, otherProduct, fromUnitId, toUnitId, "3");
        insertConversion(tenantId, productId, otherFrom, toUnitId, "4");
        insertConversion(tenantId, productId, fromUnitId, otherTo, "5");

        mockMvc.perform(get(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .param("productId", productId.toString())
                        .param("fromUnitId", fromUnitId.toString())
                        .param("toUnitId", toUnitId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void globalConversionsAppearWithoutProductFilter() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID productId = insertProduct(tenantId, toUnitId, "published");
        UUID global = insertConversion(tenantId, null, fromUnitId, toUnitId, "2");
        UUID specific =
                insertConversion(tenantId, productId, fromUnitId, toUnitId, "3");

        mockMvc.perform(get(CONVERSIONS).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.id == '%s')]", global).exists())
                .andExpect(jsonPath("$.items[?(@.id == '%s')]", specific).exists());
    }

    @Test
    void listPaginatesInDatabase() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toA = insertUnit(tenantId, "active", "unit");
        UUID toB = insertUnit(tenantId, "active", "unit");
        UUID toC = insertUnit(tenantId, "active", "unit");
        insertConversion(tenantId, null, fromUnitId, toA, "1");
        UUID expected = insertConversion(tenantId, null, fromUnitId, toB, "2");
        insertConversion(tenantId, null, fromUnitId, toC, "3");

        mockMvc.perform(get(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .param("page", "2")
                        .param("size", "1")
                        .param("sort", "factor,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void listDefaultsToCreatedAtDescending() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toA = insertUnit(tenantId, "active", "unit");
        UUID toB = insertUnit(tenantId, "active", "unit");
        UUID older = insertConversionAt(
                tenantId,
                null,
                fromUnitId,
                toA,
                "2",
                Instant.now().minus(2, ChronoUnit.DAYS));
        UUID newer = insertConversionAt(
                tenantId,
                null,
                fromUnitId,
                toB,
                "3",
                Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(get(CONVERSIONS).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.items[1].id").value(older.toString()));
    }

    @Test
    void detailReturnsConversion() throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");
        UUID conversionId = insertConversion(tenantId, null, fromUnitId, toUnitId, "2");

        mockMvc.perform(get(CONVERSIONS + "/" + conversionId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversionId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.productId").value(nullValue()))
                .andExpect(jsonPath("$.fromUnitId").value(fromUnitId.toString()))
                .andExpect(jsonPath("$.toUnitId").value(toUnitId.toString()))
                .andExpect(jsonPath("$.factor").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    private void assertInvalidCreateFactor(String factor) throws Exception {
        UUID tenantId = insertTenant(true);
        UUID fromUnitId = insertUnit(tenantId, "active", "unit");
        UUID toUnitId = insertUnit(tenantId, "active", "unit");

        mockMvc.perform(post(CONVERSIONS)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, fromUnitId, toUnitId, factor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private void denyReadPermission() {
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);
    }

    private void denyManagePermission() {
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(MANAGE_PERMISSION)))
                .willReturn(false);
    }

    private UUID insertTenant(boolean supportsUnitsAndPackaging) {
        UUID tenantId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO business_capabilities_configs
                    (id, tenant_id, preset, supports_units_and_packaging)
                VALUES (?, ?, 'custom', ?)
                """,
                UUID.randomUUID(),
                tenantId,
                supportsUnitsAndPackaging);
        return tenantId;
    }

    private UUID insertUnit(UUID tenantId, String status, String category) {
        UUID unitId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                """
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, ?, ?, ?, true, ?)
                """,
                unitId,
                tenantId,
                "U-" + suffix,
                "Unidad " + suffix,
                "u",
                category,
                status);
        return unitId;
    }

    private UUID insertProduct(UUID tenantId, UUID baseUnitId, String status) {
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Categoría', ?, 'active')
                """,
                categoryId,
                tenantId,
                "category-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO products
                    (id, tenant_id, sku, name, category_id, base_unit_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                baseUnitId,
                status);
        return productId;
    }

    private UUID insertConversion(
            UUID tenantId,
            UUID productId,
            UUID fromUnitId,
            UUID toUnitId,
            String factor) {
        UUID conversionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO unit_conversions
                    (id, tenant_id, product_id, from_unit_id, to_unit_id, factor)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                conversionId,
                tenantId,
                productId,
                fromUnitId,
                toUnitId,
                new BigDecimal(factor));
        return conversionId;
    }

    private UUID insertConversionAt(
            UUID tenantId,
            UUID productId,
            UUID fromUnitId,
            UUID toUnitId,
            String factor,
            Instant createdAt) {
        UUID conversionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO unit_conversions
                    (id, tenant_id, product_id, from_unit_id, to_unit_id, factor, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ))
                """,
                conversionId,
                tenantId,
                productId,
                fromUnitId,
                toUnitId,
                new BigDecimal(factor),
                createdAt.toString());
        return conversionId;
    }

    private void archiveUnit(UUID unitId) {
        jdbcTemplate.update("UPDATE units SET status = 'archived' WHERE id = ?", unitId);
    }

    private BigDecimal conversionFactor(UUID conversionId) {
        return jdbcTemplate.queryForObject(
                "SELECT factor FROM unit_conversions WHERE id = ?",
                BigDecimal.class,
                conversionId);
    }

    private UUID globalConversionId(UUID tenantId, UUID fromUnitId, UUID toUnitId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM unit_conversions
                WHERE tenant_id = ?
                  AND product_id IS NULL
                  AND from_unit_id = ?
                  AND to_unit_id = ?
                """,
                UUID.class,
                tenantId,
                fromUnitId,
                toUnitId);
    }

    private UUID conversionProductId(UUID conversionId) {
        return jdbcTemplate.queryForObject(
                "SELECT product_id FROM unit_conversions WHERE id = ?",
                UUID.class,
                conversionId);
    }

    private UUID conversionUnitId(UUID conversionId, String column) {
        if (!column.equals("from_unit_id") && !column.equals("to_unit_id")) {
            throw new IllegalArgumentException("Unexpected unit column");
        }
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM unit_conversions WHERE id = ?",
                UUID.class,
                conversionId);
    }

    private boolean conversionExists(UUID conversionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM unit_conversions WHERE id = ?",
                Integer.class,
                conversionId);
        return count != null && count > 0;
    }

    private int conversionCount(UUID tenantId, UUID fromUnitId, UUID toUnitId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM unit_conversions
                WHERE tenant_id = ? AND from_unit_id = ? AND to_unit_id = ?
                """,
                Integer.class,
                tenantId,
                fromUnitId,
                toUnitId);
        return count == null ? 0 : count;
    }

    private String token(UUID tenantId) {
        User user = User.builder()
                .name("Usuario catálogo")
                .email("catalogo-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(tenantId);
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private static String createBody(
            UUID productId, UUID fromUnitId, UUID toUnitId, String factor) {
        return """
                {
                  "productId": %s,
                  "fromUnitId": "%s",
                  "toUnitId": "%s",
                  "factor": %s
                }
                """
                .formatted(jsonUuid(productId), fromUnitId, toUnitId, factor);
    }

    private static String updateBody(String factor) {
        return """
                {
                  "factor": %s
                }
                """
                .formatted(factor);
    }

    private static String jsonUuid(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

}
