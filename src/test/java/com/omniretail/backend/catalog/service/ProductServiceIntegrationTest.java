package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.SubscriptionTestFixtures;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.service.BusinessConfigService;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.entity.Unit;
import com.omniretail.backend.catalog.entity.UnitCategory;
import com.omniretail.backend.catalog.repository.CategoryRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductServiceIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired private SaasPlanRepository planRepository;
    @Autowired private TenantSubscriptionRepository subscriptionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private BusinessConfigService businessConfigService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReturnsHibernateTimestampsAfterInsert() {
        Tenant tenant = tenantRepository.saveAndFlush(Tenant.builder()
                .name("Tienda catalogo")
                .slug("catalogo-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        SubscriptionTestFixtures.provisionBasic(subscriptionRepository, planRepository, tenant.getId());
        Category category = Category.builder()
                .name("Categoria")
                .slug("categoria-" + UUID.randomUUID())
                .build();
        category.setTenantId(tenant.getId());
        category = categoryRepository.saveAndFlush(category);
        Unit unit = Unit.builder()
                .code("U-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Unidad")
                .symbol("u")
                .category(UnitCategory.unit)
                .build();
        unit.setTenantId(tenant.getId());
        unit = unitRepository.saveAndFlush(unit);
        User actor = User.builder()
                .name("Usuario catalogo")
                .email("catalogo-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .build();
        actor.setTenantId(tenant.getId());
        actor = userRepository.saveAndFlush(actor);
        authenticate(actor.getId(), tenant.getId());
        given(businessConfigService.getConfig()).willReturn(new BusinessConfigResponse(
                tenant.getId(), BusinessPreset.custom,
                true, true, true, true, true, true, true, true, true,
                List.of(),
                new com.omniretail.backend.administration.dto.ProductTrackingDto(
                        true, true, true, true)));

        ProductDto created = productService.create(new ProductCreateRequest(
                "SKU-" + UUID.randomUUID(),
                null,
                "Producto con timestamps",
                null,
                null,
                ProductType.physical,
                category.getId(),
                unit.getId(),
                null,
                unit.getId(),
                new BigDecimal("10.00"),
                ProductStatus.published,
                new ProductTrackingDto(true, false, false, false),
                new ProductChannelsDto(true, true, false)));

        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
        var initialHistory = jdbcTemplate.queryForMap("""
                SELECT old_price, new_price, changed_by_user_id, reason
                FROM product_price_history
                WHERE tenant_id = ? AND product_id = ?
                """, tenant.getId(), created.id());
        assertThat(initialHistory.get("old_price")).isNull();
        assertThat((BigDecimal) initialHistory.get("new_price")).isEqualByComparingTo("10.00");
        assertThat(initialHistory.get("changed_by_user_id")).isEqualTo(actor.getId());
        assertThat(initialHistory.get("reason")).isEqualTo("Creación de producto");
    }

    private static void authenticate(UUID userId, UUID tenantId) {
        AuthenticatedUser user = new AuthenticatedUser(
                userId, tenantId, UserType.employee, null, null, UUID.randomUUID());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
