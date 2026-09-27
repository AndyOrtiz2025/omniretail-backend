package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.TenantRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
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

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UnitRepository unitRepository;

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
        authenticate(tenant.getId());

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
    }

    private static void authenticate(UUID tenantId) {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), tenantId, UserType.employee, null, null, UUID.randomUUID());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
