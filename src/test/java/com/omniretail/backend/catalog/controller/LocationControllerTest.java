package com.omniretail.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataIntegrityViolationException;
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
class LocationControllerTest {

    private static final String LOCATIONS = "/api/v1/catalog/locations";
    private static final String READ_PERMISSION = "catalog.locations.read";
    private static final String MANAGE_PERMISSION = "catalog.locations.manage";
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
        mockMvc.perform(get(LOCATIONS)).andExpect(status().isUnauthorized());
    }

    @Test
    void detailWithoutReadPermissionReturnsForbidden() throws Exception {
        Actor actor = detachedActor();
        denyReadPermission();

        mockMvc.perform(get(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(actor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void createWithoutManagePermissionReturnsForbidden() throws Exception {
        Actor actor = detachedActor();
        denyManagePermission();

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(actor))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                UUID.randomUUID(), null, "BOD-01", "Bodega", "warehouse", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void updateWithoutManagePermissionReturnsForbidden() throws Exception {
        Actor actor = detachedActor();
        denyManagePermission();

        mockMvc.perform(put(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(actor))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Bodega", "active")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void deleteWithoutManagePermissionReturnsForbidden() throws Exception {
        Actor actor = detachedActor();
        denyManagePermission();

        mockMvc.perform(delete(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(actor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void listIsBlockedWhenCapabilityIsDisabled() throws Exception {
        Fixture fixture = createFixture(false, "all");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR))
                .andExpect(jsonPath("$.message")
                        .value("La gestión de ubicaciones no está habilitada para este negocio."));
    }

    @Test
    void detailIsBlockedWhenCapabilityIsDisabled() throws Exception {
        Fixture fixture = createFixture(false, "all");

        mockMvc.perform(get(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void createIsBlockedWhenCapabilityIsDisabled() throws Exception {
        Fixture fixture = createFixture(false, "all");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "BOD", "Bodega", "warehouse", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void updateIsBlockedWhenCapabilityIsDisabled() throws Exception {
        Fixture fixture = createFixture(false, "all");

        mockMvc.perform(put(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Bodega", "active")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void deleteIsBlockedWhenCapabilityIsDisabled() throws Exception {
        Fixture fixture = createFixture(false, "all");

        mockMvc.perform(delete(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CAPABILITY_ERROR));
    }

    @Test
    void enabledCapabilityAllowsAccess() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void listDoesNotShowLocationsFromAnotherTenant() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");
        UUID expected = insertLocation(
                tenantA.tenantId(), tenantA.firstBranchId(), null, "A", "A", "warehouse", "active");
        insertLocation(
                tenantB.tenantId(), tenantB.firstBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(tenantA.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void detailFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");
        UUID location = insertLocation(
                tenantB.tenantId(), tenantB.firstBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS + "/" + location)
                        .header("Authorization", token(tenantA.actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void detailForNonexistentLocationReturnsNotFound() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(get(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void updateFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");
        UUID location = insertLocation(
                tenantB.tenantId(), tenantB.firstBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(put(LOCATIONS + "/" + location)
                        .header("Authorization", token(tenantA.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Nueva", "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void updateForNonexistentLocationReturnsNotFound() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(put(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Nueva", "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void archiveFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");
        UUID location = insertLocation(
                tenantB.tenantId(), tenantB.firstBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(delete(LOCATIONS + "/" + location)
                        .header("Authorization", token(tenantA.actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void archiveForNonexistentLocationReturnsNotFound() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(delete(LOCATIONS + "/" + UUID.randomUUID())
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    void createWithBranchFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(tenantA.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                tenantB.firstBranchId(), null, "BOD", "Bodega", "warehouse", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void createWithParentFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");
        UUID foreignParent = insertLocation(
                tenantB.tenantId(), tenantB.firstBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(tenantA.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                tenantA.firstBranchId(), foreignParent, "A-1", "Pasillo", "aisle", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_PARENT_NOT_FOUND"));
    }

    @Test
    void allBranchActorSeesAllTenantBranches() throws Exception {
        Fixture fixture = createFixture(true, "all");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "A", "A", "warehouse", "active");
        insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void limitedActorSeesOnlyAllowedBranches() throws Exception {
        Fixture fixture = createFixture(true, "assigned");
        UUID expected = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "A", "A", "warehouse", "active");
        insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void actorWithoutAllowedBranchesReceivesEmptyPage() throws Exception {
        Fixture fixture = createFixture(true, "assigned");
        clearActorBranch(fixture.actor().userId());
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "A", "A", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void branchFilterOutsideScopeReturnsForbidden() throws Exception {
        Fixture fixture = createFixture(true, "assigned");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("branchId", fixture.secondBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message")
                        .value("No tienes acceso a esta sucursal."));
    }

    @Test
    void detailOutsideScopeReturnsForbidden() throws Exception {
        Fixture fixture = createFixture(true, "assigned");
        UUID location = insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void createOutsideScopeReturnsForbidden() throws Exception {
        Fixture fixture = createFixture(true, "assigned");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.secondBranchId(), null, "B", "B", "warehouse", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void updateOutsideScopeReturnsForbidden() throws Exception {
        Fixture fixture = createFixture(true, "assigned");
        UUID location = insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(put(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Nueva", "inactive")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void deleteOutsideScopeReturnsForbidden() throws Exception {
        Fixture fixture = createFixture(true, "assigned");
        UUID location = insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(delete(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void validWarehouseWithNullParentIsCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "BOD-01", "Bodega", "warehouse", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(10)))
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.firstBranchId().toString()))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.code").value("BOD-01"))
                .andExpect(jsonPath("$.name").value("Bodega"))
                .andExpect(jsonPath("$.type").value("warehouse"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void validAisleWithWarehouseParentIsCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");

        assertCreateSucceeds(fixture, fixture.firstBranchId(), warehouse, "A-1", "aisle");
    }

    @Test
    void validShelfWithAisleParentIsCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");
        UUID aisle = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), warehouse, "A", "A", "aisle", "active");

        assertCreateSucceeds(fixture, fixture.firstBranchId(), aisle, "S-1", "shelf");
    }

    @Test
    void validShelfDirectlyUnderWarehouseIsCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");

        assertCreateSucceeds(fixture, fixture.firstBranchId(), warehouse, "S-1", "shelf");
    }

    @Test
    void validLevelWithShelfParentIsCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");
        UUID shelf = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), warehouse, "S", "S", "shelf", "active");

        assertCreateSucceeds(fixture, fixture.firstBranchId(), shelf, "L-1", "level");
    }

    @Test
    void createDefaultsStatusToActive() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "W", "W", "warehouse", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void createNormalizesCodeAndTrimsName() throws Exception {
        Fixture fixture = createFixture(true, "all");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "  pasillo 1 ", "  Bodega Norte  ", "warehouse", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PASILLO-1"))
                .andExpect(jsonPath("$.name").value("Bodega Norte"));
    }

    @Test
    void inactiveBranchIsRejectedOnCreate() throws Exception {
        Fixture fixture = createFixture(true, "all");
        setBranchStatus(fixture.firstBranchId(), "inactive");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "W", "W", "warehouse", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Sucursal no encontrada o inactiva."));
    }

    @Test
    void warehouseBranchTypeCanContainLocations() throws Exception {
        Fixture fixture = createFixture(true, "all");
        setBranchType(fixture.firstBranchId(), "warehouse");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "W", "W", "warehouse", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void warehouseWithParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = warehouse(fixture, "W");

        assertHierarchyRejected(fixture, parent, "W-2", "warehouse");
    }

    @Test
    void aisleWithoutParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");

        assertHierarchyRejected(fixture, null, "A", "aisle");
    }

    @Test
    void aisleWithAisleParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");
        UUID aisle = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), warehouse, "A", "A", "aisle", "active");

        assertHierarchyRejected(fixture, aisle, "A-2", "aisle");
    }

    @Test
    void shelfWithoutParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");

        assertHierarchyRejected(fixture, null, "S", "shelf");
    }

    @Test
    void levelWithoutParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");

        assertHierarchyRejected(fixture, null, "L", "level");
    }

    @Test
    void levelWithWarehouseParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");

        assertHierarchyRejected(fixture, warehouse, "L", "level");
    }

    @Test
    void levelWithAisleParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID warehouse = warehouse(fixture, "W");
        UUID aisle = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), warehouse, "A", "A", "aisle", "active");

        assertHierarchyRejected(fixture, aisle, "L", "level");
    }

    @Test
    void parentFromAnotherBranchIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "W", "W", "warehouse", "active");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), parent, "A", "A", "aisle", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_HIERARCHY_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("La ubicación padre debe pertenecer a la misma sucursal."));
    }

    @Test
    void inactiveParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W", "W", "warehouse", "inactive");

        assertInactiveParentRejected(fixture, parent);
    }

    @Test
    void archivedParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W", "W", "warehouse", "archived");

        assertInactiveParentRejected(fixture, parent);
    }

    @Test
    void duplicateNormalizedCodeInSameBranchReturnsConflict() throws Exception {
        Fixture fixture = createFixture(true, "all");
        warehouse(fixture, "PASILLO-1");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "  pasillo 1 ", "Otra", "warehouse", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_CODE_CONFLICT"));
    }

    @Test
    void sameCodeInDifferentBranchIsAllowed() throws Exception {
        Fixture fixture = createFixture(true, "all");
        warehouse(fixture, "W");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.secondBranchId(), null, "W", "W", "warehouse", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void sameCodeInDifferentTenantIsAllowed() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");
        warehouse(tenantB, "W");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(tenantA.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                tenantA.firstBranchId(), null, "W", "W", "warehouse", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateNamesAreAllowed() throws Exception {
        Fixture fixture = createFixture(true, "all");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W-1", "Bodega", "warehouse", "active");

        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "W-2", "Bodega", "warehouse", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void explicitActiveStatusCanBeCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");

        assertCreateWithStatus(fixture, "active");
    }

    @Test
    void inactiveStatusCanBeCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");

        assertCreateWithStatus(fixture, "inactive");
    }

    @Test
    void archivedStatusCanBeCreated() throws Exception {
        Fixture fixture = createFixture(true, "all");

        assertCreateWithStatus(fixture, "archived");
    }

    @Test
    void updateChangesOnlyNameAndStatus() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");

        mockMvc.perform(put(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("  Nombre actualizado  ", "inactive")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(location.toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.firstBranchId().toString()))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.code").value("W"))
                .andExpect(jsonPath("$.name").value("Nombre actualizado"))
                .andExpect(jsonPath("$.type").value("warehouse"))
                .andExpect(jsonPath("$.status").value("inactive"));
    }

    @Test
    void inactiveLocationCanBeReactivated() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W", "W", "warehouse", "inactive");

        assertUpdateStatus(fixture, location, "active");
    }

    @Test
    void activeLocationCanBeArchivedThroughPut() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");

        assertUpdateStatus(fixture, location, "archived");
        assertThat(locationStatus(location)).isEqualTo("archived");
    }

    @Test
    void archivedLocationCanBeReactivatedWithActiveParent() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = warehouse(fixture, "W");
        UUID child = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A", "A", "aisle", "archived");

        assertUpdateStatus(fixture, child, "active");
    }

    @Test
    void reactivationWithInactiveParentIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W", "W", "warehouse", "inactive");
        UUID child = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A", "A", "aisle", "archived");

        mockMvc.perform(put(LOCATIONS + "/" + child)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("A", "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_PARENT_INACTIVE"));
    }

    @Test
    void reactivationWithInactiveBranchIsRejected() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W", "W", "warehouse", "inactive");
        setBranchStatus(fixture.firstBranchId(), "inactive");

        mockMvc.perform(put(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("W", "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_BRANCH_INACTIVE"));
    }

    @Test
    void archiveReturnsNoContentAndKeepsRow() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");

        mockMvc.perform(delete(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNoContent());

        assertThat(locationExists(location)).isTrue();
        assertThat(locationStatus(location)).isEqualTo("archived");
    }

    @Test
    void archiveIsIdempotent() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "W", "W", "warehouse", "archived");

        mockMvc.perform(delete(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNoContent());

        assertThat(locationStatus(location)).isEqualTo("archived");
    }

    @Test
    void activeChildrenBlockArchive() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = warehouse(fixture, "W");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A", "A", "aisle", "active");

        mockMvc.perform(delete(LOCATIONS + "/" + parent)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_HAS_ACTIVE_CHILDREN"))
                .andExpect(jsonPath("$.message")
                        .value("La ubicación tiene ubicaciones hijas activas."));
    }

    @Test
    void inactiveAndArchivedChildrenDoNotBlockArchive() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = warehouse(fixture, "W");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A-1", "A", "aisle", "inactive");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A-2", "A", "aisle", "archived");

        mockMvc.perform(delete(LOCATIONS + "/" + parent)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNoContent());
    }

    @Test
    void positiveQuantityBlocksArchive() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());
        insertBalance(fixture, product, location, "2", "0");

        assertStockBlocksArchive(fixture, location);
    }

    @Test
    void positiveQuantityBlocksArchiveThroughPut() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());
        insertBalance(fixture, product, location, "2", "0");

        mockMvc.perform(put(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("W", "archived")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_HAS_STOCK"));
    }

    @Test
    void reservedQuantityBlocksArchive() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());
        insertBalance(fixture, product, location, "2", "1");

        assertStockBlocksArchive(fixture, location);
    }

    @Test
    void zeroBalanceAllowsArchive() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());
        insertBalance(fixture, product, location, "0", "0");

        mockMvc.perform(delete(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNoContent());
    }

    @Test
    void historicalMovementsDoNotBlockArchive() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());
        insertMovement(fixture, product, location, null);

        mockMvc.perform(delete(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isNoContent());

        assertThat(movementCountForLocation(location)).isEqualTo(1);
    }

    @Test
    void listReturnsPageResponse() throws Exception {
        Fixture fixture = createFixture(true, "all");
        warehouse(fixture, "W");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listFiltersByBranch() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID expected = warehouse(fixture, "A");
        insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "B", "B", "warehouse", "active");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void branchFilterFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture(true, "all");
        Fixture tenantB = createFixture(true, "all");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(tenantA.actor()))
                        .param("branchId", tenantB.firstBranchId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void listFiltersByParent() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parentA = warehouse(fixture, "W-A");
        UUID parentB = warehouse(fixture, "W-B");
        UUID expected = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parentA, "A-1", "A", "aisle", "active");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parentB, "A-2", "A", "aisle", "active");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("parentId", parentA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listFiltersByType() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = warehouse(fixture, "W");
        UUID expected = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A", "A", "aisle", "active");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("type", "aisle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID expected = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), null, "I", "I", "warehouse", "inactive");
        warehouse(fixture, "A");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listAppliesCombinedFilters() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID parent = warehouse(fixture, "W");
        UUID expected = insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A-1", "A", "aisle", "inactive");
        insertLocation(
                fixture.tenantId(), fixture.firstBranchId(), parent, "A-2", "A", "aisle", "active");
        insertLocation(
                fixture.tenantId(), fixture.secondBranchId(), null, "W-2", "W", "warehouse", "inactive");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("parentId", parent.toString())
                        .param("type", "aisle")
                        .param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void listPaginatesInDatabase() throws Exception {
        Fixture fixture = createFixture(true, "all");
        warehouse(fixture, "A");
        UUID expected = warehouse(fixture, "B");
        warehouse(fixture, "C");

        mockMvc.perform(get(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .param("page", "2")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void listDefaultsToCodeAscending() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID c = warehouse(fixture, "C");
        UUID a = warehouse(fixture, "A");
        UUID b = warehouse(fixture, "B");

        mockMvc.perform(get(LOCATIONS).header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(a.toString()))
                .andExpect(jsonPath("$.items[1].id").value(b.toString()))
                .andExpect(jsonPath("$.items[2].id").value(c.toString()));
    }

    @Test
    void detailReturnsCompleteResponse() throws Exception {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");

        mockMvc.perform(get(LOCATIONS + "/" + location)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(10)))
                .andExpect(jsonPath("$.id").value(location.toString()))
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.firstBranchId().toString()))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.code").value("W"))
                .andExpect(jsonPath("$.name").value("W"))
                .andExpect(jsonPath("$.type").value("warehouse"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void validLocationCanBeReferencedByInventoryBalance() {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());

        insertBalance(fixture, product, location, "0", "0");

        assertThat(balanceCountForLocation(location)).isEqualTo(1);
    }

    @Test
    void validLocationCanBeReferencedByInventoryMovement() {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());

        insertMovement(fixture, product, location, location);

        assertThat(movementCountForLocation(location)).isEqualTo(1);
    }

    @Test
    void nonexistentLocationViolatesInventoryBalanceForeignKey() {
        Fixture fixture = createFixture(true, "all");
        UUID product = insertProduct(fixture.tenantId());

        assertThatThrownBy(() -> insertBalance(
                        fixture, product, UUID.randomUUID(), "0", "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonexistentLocationViolatesInventoryMovementForeignKey() {
        Fixture fixture = createFixture(true, "all");
        UUID product = insertProduct(fixture.tenantId());

        assertThatThrownBy(() -> insertMovement(
                        fixture, product, UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonexistentToLocationViolatesInventoryMovementForeignKey() {
        Fixture fixture = createFixture(true, "all");
        UUID product = insertProduct(fixture.tenantId());

        assertThatThrownBy(() -> insertMovement(
                        fixture, product, null, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void movementForeignKeyRestrictsPhysicalLocationDeletion() {
        Fixture fixture = createFixture(true, "all");
        UUID location = warehouse(fixture, "W");
        UUID product = insertProduct(fixture.tenantId());
        insertMovement(fixture, product, location, null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM locations WHERE id = ?", location))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullLocationRemainsAllowedForBalancesAndMovements() {
        Fixture fixture = createFixture(true, "all");
        UUID product = insertProduct(fixture.tenantId());

        insertBalance(fixture, product, null, "0", "0");
        insertMovement(fixture, product, null, null);

        assertThat(nullLocationBalanceCount(fixture.tenantId())).isEqualTo(1);
        assertThat(nullLocationMovementCount(fixture.tenantId())).isEqualTo(1);
    }

    private void assertCreateSucceeds(
            Fixture fixture,
            UUID branchId,
            UUID parentId,
            String code,
            String type) throws Exception {
        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(branchId, parentId, code, code, type, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(parentId.toString()))
                .andExpect(jsonPath("$.type").value(type));
    }

    private void assertHierarchyRejected(
            Fixture fixture, UUID parentId, String code, String type) throws Exception {
        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), parentId, code, code, type, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_HIERARCHY_INVALID"));
    }

    private void assertInactiveParentRejected(Fixture fixture, UUID parentId)
            throws Exception {
        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), parentId, "A", "A", "aisle", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_PARENT_INACTIVE"));
    }

    private void assertCreateWithStatus(Fixture fixture, String locationStatus)
            throws Exception {
        mockMvc.perform(post(LOCATIONS)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture.firstBranchId(), null, "W", "W", "warehouse", locationStatus)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(locationStatus));
    }

    private void assertUpdateStatus(Fixture fixture, UUID locationId, String statusValue)
            throws Exception {
        mockMvc.perform(put(LOCATIONS + "/" + locationId)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody("Actualizada", statusValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(statusValue));
    }

    private void assertStockBlocksArchive(Fixture fixture, UUID locationId)
            throws Exception {
        mockMvc.perform(delete(LOCATIONS + "/" + locationId)
                        .header("Authorization", token(fixture.actor())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_HAS_STOCK"))
                .andExpect(jsonPath("$.message")
                        .value("La ubicación contiene inventario y no puede archivarse."));
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

    private Fixture createFixture(boolean supportsMultipleLocations, String branchScope) {
        UUID tenantId = insertTenant(supportsMultipleLocations);
        UUID firstBranchId = insertBranch(tenantId, "active");
        UUID secondBranchId = insertBranch(tenantId, "active");
        Actor actor = insertActor(tenantId, firstBranchId, branchScope);
        return new Fixture(tenantId, firstBranchId, secondBranchId, actor);
    }

    private UUID insertTenant(boolean supportsMultipleLocations) {
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
                    (id, tenant_id, preset, supports_multiple_locations)
                VALUES (?, ?, 'custom', ?)
                """,
                UUID.randomUUID(),
                tenantId,
                supportsMultipleLocations);
        return tenantId;
    }

    private UUID insertBranch(UUID tenantId, String statusValue) {
        UUID branchId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'store', ?)
                """,
                branchId,
                tenantId,
                "BR-" + suffix,
                "Sucursal " + suffix,
                statusValue);
        return branchId;
    }

    private Actor insertActor(UUID tenantId, UUID branchId, String branchScope) {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO roles
                    (id, tenant_id, name, is_system, permissions, branch_scope, status)
                VALUES (?, ?, ?, false, '{}', ?, 'active')
                """,
                roleId,
                tenantId,
                "Rol " + suffix,
                branchScope);
        jdbcTemplate.update(
                """
                INSERT INTO users
                    (id, tenant_id, name, email, type, status, role_id, branch_id,
                     allowed_branch_ids)
                VALUES (?, ?, ?, ?, 'employee', 'active', ?, ?, NULL)
                """,
                userId,
                tenantId,
                "Usuario " + suffix,
                "location-" + suffix + "@test.local",
                roleId,
                branchId);
        return new Actor(userId, tenantId, roleId, branchId);
    }

    private Actor detachedActor() {
        return new Actor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private void clearActorBranch(UUID userId) {
        jdbcTemplate.update(
                "UPDATE users SET branch_id = NULL, allowed_branch_ids = '{}' WHERE id = ?",
                userId);
    }

    private UUID warehouse(Fixture fixture, String code) {
        return insertLocation(
                fixture.tenantId(),
                fixture.firstBranchId(),
                null,
                code,
                code,
                "warehouse",
                "active");
    }

    private UUID insertLocation(
            UUID tenantId,
            UUID branchId,
            UUID parentId,
            String code,
            String name,
            String type,
            String statusValue) {
        UUID locationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO locations
                    (id, tenant_id, branch_id, parent_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                locationId,
                tenantId,
                branchId,
                parentId,
                code,
                name,
                type,
                statusValue);
        return locationId;
    }

    private UUID insertProduct(UUID tenantId) {
        UUID unitId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, ?, 'u', 'unit', true, 'active')
                """,
                unitId,
                tenantId,
                "U-" + suffix.substring(0, 8),
                "Unidad " + suffix);
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
                VALUES (?, ?, ?, ?, ?, ?, 'published')
                """,
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
        return productId;
    }

    private void insertBalance(
            Fixture fixture,
            UUID productId,
            UUID locationId,
            String quantity,
            String reservedQuantity) {
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id,
                     quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.firstBranchId(),
                productId,
                locationId,
                new BigDecimal(quantity),
                new BigDecimal(reservedQuantity));
    }

    private void insertMovement(
            Fixture fixture, UUID productId, UUID fromLocationId, UUID toLocationId) {
        jdbcTemplate.update(
                """
                INSERT INTO inventory_movements
                    (id, tenant_id, branch_id, product_id, type, reason, quantity,
                     from_location_id, to_location_id)
                VALUES (?, ?, ?, ?, 'transfer', 'Movimiento histórico', 1, ?, ?)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.firstBranchId(),
                productId,
                fromLocationId,
                toLocationId);
    }

    private void setBranchStatus(UUID branchId, String statusValue) {
        jdbcTemplate.update(
                "UPDATE branches SET status = ? WHERE id = ?", statusValue, branchId);
    }

    private void setBranchType(UUID branchId, String type) {
        jdbcTemplate.update("UPDATE branches SET type = ? WHERE id = ?", type, branchId);
    }

    private String locationStatus(UUID locationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM locations WHERE id = ?", String.class, locationId);
    }

    private boolean locationExists(UUID locationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE id = ?", Integer.class, locationId);
        return count != null && count > 0;
    }

    private int balanceCountForLocation(UUID locationId) {
        return count(
                "SELECT COUNT(*) FROM inventory_balances WHERE location_id = ?",
                locationId);
    }

    private int movementCountForLocation(UUID locationId) {
        return count(
                """
                SELECT COUNT(*) FROM inventory_movements
                WHERE from_location_id = ? OR to_location_id = ?
                """,
                locationId,
                locationId);
    }

    private int nullLocationBalanceCount(UUID tenantId) {
        return count(
                """
                SELECT COUNT(*) FROM inventory_balances
                WHERE tenant_id = ? AND location_id IS NULL
                """,
                tenantId);
    }

    private int nullLocationMovementCount(UUID tenantId) {
        return count(
                """
                SELECT COUNT(*) FROM inventory_movements
                WHERE tenant_id = ?
                  AND from_location_id IS NULL
                  AND to_location_id IS NULL
                """,
                tenantId);
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private String token(Actor actor) {
        User user = User.builder()
                .name("Usuario locations")
                .email("locations-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(actor.roleId())
                .branchId(actor.branchId())
                .build();
        ReflectionTestUtils.setField(user, "id", actor.userId());
        user.setTenantId(actor.tenantId());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(actor.userId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private static String createBody(
            UUID branchId,
            UUID parentId,
            String code,
            String name,
            String type,
            String statusValue) {
        return """
                {
                  "branchId": "%s",
                  "parentId": %s,
                  "code": "%s",
                  "name": "%s",
                  "type": "%s",
                  "status": %s
                }
                """
                .formatted(
                        branchId,
                        jsonUuid(parentId),
                        code,
                        name,
                        type,
                        jsonString(statusValue));
    }

    private static String updateBody(String name, String statusValue) {
        return """
                {
                  "name": "%s",
                  "status": "%s"
                }
                """
                .formatted(name, statusValue);
    }

    private static String jsonUuid(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private record Actor(UUID userId, UUID tenantId, UUID roleId, UUID branchId) {}

    private record Fixture(
            UUID tenantId,
            UUID firstBranchId,
            UUID secondBranchId,
            Actor actor) {}
}
