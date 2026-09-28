package com.omniretail.backend.catalog.controller;

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
class CategoryControllerTest {

    private static final String CATEGORIES = "/api/v1/catalog/categories";
    private static final String READ_PERMISSION = "catalog.categories.read";
    private static final String MANAGE_PERMISSION = "catalog.categories.manage";

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
    void getWithoutAuthenticationReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(CATEGORIES)).andExpect(status().isUnauthorized());
    }

    @Test
    void getWithoutReadPermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);

        mockMvc.perform(get(CATEGORIES).header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void postWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, "Categoría", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void putWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(put(CATEGORIES + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Categoría", null, "active")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void deleteWithoutManagePermissionReturnsForbidden() throws Exception {
        UUID tenantId = UUID.randomUUID();
        denyManagePermission();

        mockMvc.perform(delete(CATEGORIES + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void emptyListReturnsEmptyArray() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(get(CATEGORIES).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listOnlyReturnsCurrentTenantCategories() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID expected = insertCategory(tenantA, null, "Tenant A", "tenant-a", "active");
        insertCategory(tenantB, null, "Tenant B", "tenant-b", "active");

        mockMvc.perform(get(CATEGORIES).header("Authorization", token(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(expected.toString()))
                .andExpect(jsonPath("$[0].tenantId").value(tenantA.toString()));
    }

    @Test
    void activeStatusFilterOnlyReturnsActiveCategories() throws Exception {
        UUID tenantId = insertTenant();
        UUID active = insertCategory(tenantId, null, "Activa", "activa", "active");
        insertCategory(tenantId, null, "Archivada", "archivada", "archived");

        mockMvc.perform(get(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(active.toString()))
                .andExpect(jsonPath("$[0].status").value("active"));
    }

    @Test
    void archivedStatusFilterOnlyReturnsArchivedCategories() throws Exception {
        UUID tenantId = insertTenant();
        insertCategory(tenantId, null, "Activa", "activa", "active");
        UUID archived =
                insertCategory(tenantId, null, "Archivada", "archivada", "archived");

        mockMvc.perform(get(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .param("status", "archived"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(archived.toString()))
                .andExpect(jsonPath("$[0].status").value("archived"));
    }

    @Test
    void listIsOrderedByNameAscending() throws Exception {
        UUID tenantId = insertTenant();
        insertCategory(tenantId, null, "Zulu", "zulu", "active");
        insertCategory(tenantId, null, "Alpha", "alpha", "active");
        insertCategory(tenantId, null, "Medio", "medio", "active");

        mockMvc.perform(get(CATEGORIES).header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alpha"))
                .andExpect(jsonPath("$[1].name").value("Medio"))
                .andExpect(jsonPath("$[2].name").value("Zulu"));
    }

    @Test
    void getByIdReturnsCompleteCategoryResponse() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId = insertCategory(tenantId, null, "Padre", "padre", "active");
        UUID categoryId = insertCategory(
                tenantId,
                parentId,
                "Electrónica",
                "electronica",
                "active",
                "Descripción",
                "https://example.com/category.png");

        mockMvc.perform(get(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(categoryId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.parentId").value(parentId.toString()))
                .andExpect(jsonPath("$.name").value("Electrónica"))
                .andExpect(jsonPath("$.slug").value("electronica"))
                .andExpect(jsonPath("$.description").value("Descripción"))
                .andExpect(jsonPath("$.imageUrl")
                        .value("https://example.com/category.png"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void nonexistentCategoryReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(get(CATEGORIES + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Categoría no encontrada."));
    }

    @Test
    void categoryFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID categoryB = insertCategory(tenantB, null, "Ajena", "ajena", "active");

        mockMvc.perform(get(CATEGORIES + "/" + categoryB)
                        .header("Authorization", token(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void createRootCategoryTrimsNameAndDefaultsStatus() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                null, "  Hogar y Cocina  ", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.name").value("Hogar y Cocina"))
                .andExpect(jsonPath("$.slug").value("hogar-y-cocina"))
                .andExpect(jsonPath("$.description").value("Descripción"))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/image.png"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void createChildCategoryUsesActiveParent() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId = insertCategory(tenantId, null, "Padre", "padre", "active");

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(parentId, "Hija", null, "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(parentId.toString()))
                .andExpect(jsonPath("$.name").value("Hija"));
    }

    @Test
    void createGeneratesSlugFromAccentedName() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, "Electrónica", "   ", "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("electronica"));
    }

    @Test
    void createNormalizesExplicitSlug() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                null, "Audio", "  Audio & Vídeo---Profesional  ", "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("audio-video-profesional"));
    }

    @Test
    void createWithNonexistentParentReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                UUID.randomUUID(), "Hija", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_PARENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Categoría padre no encontrada o inactiva."));
    }

    @Test
    void createWithParentFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID parentB = insertCategory(tenantB, null, "Padre B", "padre-b", "active");

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(parentB, "Hija", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_PARENT_NOT_FOUND"));
    }

    @Test
    void createWithArchivedParentReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId =
                insertCategory(tenantId, null, "Archivada", "archivada", "archived");

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(parentId, "Hija", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_PARENT_NOT_FOUND"));
    }

    @Test
    void duplicateSlugInSameTenantReturnsConflict() throws Exception {
        UUID tenantId = insertTenant();
        insertCategory(tenantId, null, "Existente", "audio-video", "active");

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                null, "Otra", "Audio & Video", "active")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SLUG_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Ya existe una categoría con ese slug."));
    }

    @Test
    void sameSlugInDifferentTenantsIsAllowed() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        insertCategory(tenantB, null, "Existente", "compartido", "active");

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, "Nueva", "Compartido", "active")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("compartido"));
    }

    @Test
    void normalizedEmptySlugReturnsBadRequest() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, "Nombre", "###", "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_SLUG_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("El slug de la categoría no es válido."));
    }

    @Test
    void createRequestBeanValidationIsEnforced() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(null, "   ", null, "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post(CATEGORIES)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                null, "Nombre", "x".repeat(201), "active")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateReplacesEditableFields() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId = insertCategory(tenantId, null, "Padre", "padre", "active");
        UUID categoryId = insertCategory(tenantId, null, "Anterior", "anterior", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(
                                parentId, "  Actualizada  ", "Nuevo Slug", "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(parentId.toString()))
                .andExpect(jsonPath("$.name").value("Actualizada"))
                .andExpect(jsonPath("$.slug").value("nuevo-slug"))
                .andExpect(jsonPath("$.description").value("Descripción actualizada"))
                .andExpect(jsonPath("$.imageUrl")
                        .value("https://example.com/updated.png"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void updateKeepsCurrentSlugWhenSlugIsNullOrBlank() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId = insertCategory(tenantId, null, "Original", "slug-estable", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Renombrada", null, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("slug-estable"));

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Renombrada otra vez", "   ", "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("slug-estable"));
    }

    @Test
    void updateCanChangeSlugExplicitly() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId = insertCategory(tenantId, null, "Categoría", "anterior", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(
                                null, "Categoría", "  Electrónica & Audio ", "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("electronica-audio"));
    }

    @Test
    void updateToDuplicateSlugReturnsConflict() throws Exception {
        UUID tenantId = insertTenant();
        insertCategory(tenantId, null, "Primera", "ocupado", "active");
        UUID categoryId = insertCategory(tenantId, null, "Segunda", "libre", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Segunda", "Ocupado", "active")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SLUG_CONFLICT"));
    }

    @Test
    void updateCanConvertChildIntoRoot() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId = insertCategory(tenantId, null, "Padre", "padre", "active");
        UUID categoryId =
                insertCategory(tenantId, parentId, "Hija", "hija", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Hija", null, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(nullValue()));
    }

    @Test
    void updateCategoryFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID categoryB = insertCategory(tenantB, null, "Ajena", "ajena", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryB)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Ajena", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void updateWithNonexistentParentReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId = insertCategory(tenantId, null, "Categoría", "categoria", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(
                                UUID.randomUUID(), "Categoría", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_PARENT_NOT_FOUND"));
    }

    @Test
    void selfParentReturnsCycleConflict() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId = insertCategory(tenantId, null, "Categoría", "categoria", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(categoryId, "Categoría", null, "active")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"))
                .andExpect(jsonPath("$.message")
                        .value("La jerarquía de categorías no puede contener ciclos."));
    }

    @Test
    void indirectCycleReturnsCycleConflict() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryA = insertCategory(tenantId, null, "A", "a", "active");
        UUID categoryB = insertCategory(tenantId, categoryA, "B", "b", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryA)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(categoryB, "A", null, "active")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
    }

    @Test
    void updateWithArchivedParentReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId =
                insertCategory(tenantId, null, "Archivada", "archivada", "archived");
        UUID categoryId = insertCategory(tenantId, null, "Categoría", "categoria", "active");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(parentId, "Categoría", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_PARENT_NOT_FOUND"));
    }

    @Test
    void deleteArchivesCategoryAndReturnsNoContent() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId = insertCategory(tenantId, null, "Categoría", "categoria", "active");

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(categoryStatus(categoryId))
                .isEqualTo("archived");
    }

    @Test
    void deleteNonexistentCategoryReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();

        mockMvc.perform(delete(CATEGORIES + "/" + UUID.randomUUID())
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void deleteCategoryFromAnotherTenantReturnsNotFound() throws Exception {
        UUID tenantA = insertTenant();
        UUID tenantB = insertTenant();
        UUID categoryB = insertCategory(tenantB, null, "Ajena", "ajena", "active");

        mockMvc.perform(delete(CATEGORIES + "/" + categoryB)
                        .header("Authorization", token(tenantA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void deleteAlreadyArchivedCategoryIsIdempotent() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId =
                insertCategory(tenantId, null, "Archivada", "archivada", "archived");

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(categoryStatus(categoryId))
                .isEqualTo("archived");
    }

    @Test
    void deleteAllowsArchivingCategoryWithAssociatedProducts() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId = insertCategory(tenantId, null, "Categoría", "categoria", "active");
        insertProduct(tenantId, categoryId);

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(categoryStatus(categoryId))
                .isEqualTo("archived");
    }

    @Test
    void deleteIsBlockedWhenCategoryHasActiveChildren() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId = insertCategory(tenantId, null, "Padre", "padre", "active");
        insertCategory(tenantId, parentId, "Hija", "hija", "active");

        mockMvc.perform(delete(CATEGORIES + "/" + parentId)
                        .header("Authorization", token(tenantId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_ACTIVE_CHILDREN"))
                .andExpect(jsonPath("$.message")
                        .value("No se puede archivar una categoría que tiene categorías hijas activas."));

        org.assertj.core.api.Assertions.assertThat(categoryStatus(parentId))
                .isEqualTo("active");
    }

    @Test
    void updateArchiveIsBlockedWhenCategoryHasActiveChildren() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId = insertCategory(tenantId, null, "Padre", "padre", "active");
        insertCategory(tenantId, parentId, "Hija", "hija", "active");

        mockMvc.perform(put(CATEGORIES + "/" + parentId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Padre", null, "archived")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_ACTIVE_CHILDREN"));
    }

    @Test
    void archivedCategoryCanBeReactivated() throws Exception {
        UUID tenantId = insertTenant();
        UUID categoryId =
                insertCategory(tenantId, null, "Archivada", "archivada", "archived");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(null, "Reactivada", null, "active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.slug").value("archivada"));
    }

    @Test
    void reactivationWithArchivedParentReturnsNotFound() throws Exception {
        UUID tenantId = insertTenant();
        UUID parentId =
                insertCategory(tenantId, null, "Padre", "padre", "archived");
        UUID categoryId =
                insertCategory(tenantId, parentId, "Hija", "hija", "archived");

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .header("Authorization", token(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(parentId, "Hija", null, "active")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_PARENT_NOT_FOUND"));
    }

    private void denyManagePermission() {
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(MANAGE_PERMISSION)))
                .willReturn(false);
    }

    private UUID insertTenant() {
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
        return tenantId;
    }

    private UUID insertCategory(
            UUID tenantId,
            UUID parentId,
            String name,
            String slug,
            String status) {
        return insertCategory(
                tenantId, parentId, name, slug, status, null, null);
    }

    private UUID insertCategory(
            UUID tenantId,
            UUID parentId,
            String name,
            String slug,
            String status,
            String description,
            String imageUrl) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO categories
                    (id, tenant_id, parent_id, name, slug, description, image_url, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                categoryId,
                tenantId,
                parentId,
                name,
                slug,
                description,
                imageUrl,
                status);
        return categoryId;
    }

    private void insertProduct(UUID tenantId, UUID categoryId) {
        UUID unitId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
                """,
                unitId,
                tenantId,
                "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
    }

    private String categoryStatus(UUID categoryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM categories WHERE id = ?",
                String.class,
                categoryId);
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
            UUID parentId, String name, String slug, String status) {
        return """
                {
                  "parentId": %s,
                  "name": "%s",
                  "slug": %s,
                  "description": "Descripción",
                  "imageUrl": "https://example.com/image.png",
                  "status": %s
                }
                """
                .formatted(
                        jsonUuid(parentId),
                        name,
                        jsonString(slug),
                        jsonString(status));
    }

    private static String updateBody(
            UUID parentId, String name, String slug, String status) {
        return """
                {
                  "parentId": %s,
                  "name": "%s",
                  "slug": %s,
                  "description": "Descripción actualizada",
                  "imageUrl": "https://example.com/updated.png",
                  "status": "%s"
                }
                """
                .formatted(jsonUuid(parentId), name, jsonString(slug), status);
    }

    private static String jsonUuid(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
