package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.media.MediaStorageService;
import com.omniretail.backend.shared.media.TestImageFixtures;
import com.omniretail.backend.shared.security.PermissionResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CategoryMediaRollbackIntegrationTest {

    private static final Pattern IMAGE_URL = Pattern.compile("\\\"imageUrl\\\":\\\"([^\\\"]+)\\\"");
    private static final Path MEDIA_DIRECTORY = createMediaDirectory();

    @DynamicPropertySource
    static void mediaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage-path", () -> MEDIA_DIRECTORY.toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MediaStorageService mediaStorageService;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private SessionService sessionService;
    @MockitoBean private PermissionResolver permissionResolver;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), anyString())).willReturn(true);
    }

    @AfterAll
    static void removeIsolatedMediaDirectory() throws IOException {
        Path staging = MEDIA_DIRECTORY.resolveSibling("." + MEDIA_DIRECTORY.getFileName() + "-staging");
        deleteTree(MEDIA_DIRECTORY);
        deleteTree(staging);
    }

    @Test
    void realSpringJpaRollbackDeletesNewFileRestoresReferenceAndKeepsPreviousImage() {
        UUID tenantId = insertTenant();
        UUID categoryId = UUID.randomUUID();
        String previousUrl = mediaStorageService.storeImage(
                tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId, png("previous.png"));
        insertCategory(tenantId, categoryId, previousUrl);
        Path previousFile = pathFor(previousUrl);
        AtomicReference<String> rolledBackUrl = new AtomicReference<>();

        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> {
                try {
                    String body = mockMvc.perform(multipart("/api/v1/catalog/categories/" + categoryId + "/image")
                                    .file(png("replacement.png"))
                                    .header("Authorization", token(tenantId)))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    Matcher matcher = IMAGE_URL.matcher(body);
                    assertThat(matcher.find()).isTrue();
                    rolledBackUrl.set(matcher.group(1));
                    assertThat(pathFor(rolledBackUrl.get())).isRegularFile();
                    status.setRollbackOnly();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });

            assertThat(rolledBackUrl).doesNotHaveNullValue();
            assertThat(pathFor(rolledBackUrl.get())).doesNotExist();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT image_url FROM categories WHERE id = ?", String.class, categoryId))
                    .isEqualTo(previousUrl);
            assertThat(previousFile).isRegularFile();
        } finally {
            mediaStorageService.deleteQuietly(
                    previousUrl, tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
        }
    }

    private UUID insertTenant() {
        UUID tenantId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """, tenantId, "Rollback " + suffix, "rollback-" + suffix);
        return tenantId;
    }

    private void insertCategory(UUID tenantId, UUID categoryId, String imageUrl) {
        jdbcTemplate.update("""
                INSERT INTO categories (id, tenant_id, name, slug, image_url, status)
                VALUES (?, ?, 'Rollback media', ?, ?, 'active')
                """, categoryId, tenantId, "rollback-media-" + UUID.randomUUID(), imageUrl);
    }

    private String token(UUID tenantId) {
        User user = User.builder().name("Rollback tester")
                .email("rollback-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee).roleId(UUID.randomUUID()).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(tenantId);
        Session session = Session.builder().id(UUID.randomUUID()).userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private Path pathFor(String url) {
        return mediaStorageService.root().resolve(url.substring("/media/".length()));
    }

    private static MockMultipartFile png(String filename) {
        return new MockMultipartFile("file", filename, "image/png", TestImageFixtures.png());
    }

    private static Path createMediaDirectory() {
        try {
            return Files.createTempDirectory("omniretail-media-rollback-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
