package com.omniretail.backend.ecommerce.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.HeroBannerConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.HeroBannerConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PublicStorefrontConfigControllerTest {

    private static final String BASE_URL = "/api/v1/public/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private EcommerceConfigRepository ecommerceConfigRepository;

    @Autowired
    private HeroBannerConfigRepository heroBannerConfigRepository;

    @Test
    void anonymousRequestReturnsThePublicStorefrontConfiguration() throws Exception {
        Tenant tenant = persistStorefront();

        mockMvc.perform(get(configUrl(tenant.getSlug())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.storeName").value("Mi tienda"))
                .andExpect(jsonPath("$.logoUrl").value("https://cdn.example.com/logo.png"))
                .andExpect(jsonPath("$.guestTrackingEnabled").value(true))
                .andExpect(jsonPath("$.slides[0].title").value("Oferta de temporada"))
                .andExpect(jsonPath("$.slides[0].imageUrl").value("https://cdn.example.com/banner.png"));
    }

    @Test
    void unknownSlugReturnsStorefrontNotFound() throws Exception {
        mockMvc.perform(get(configUrl("tienda-inexistente-" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOREFRONT_NOT_FOUND"));
    }

    private Tenant persistStorefront() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Tienda " + suffix)
                .slug("tienda-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());

        EcommerceConfig config = EcommerceConfig.builder()
                .enabled(true)
                .storeName("Mi tienda")
                .logoUrl("https://cdn.example.com/logo.png")
                .contactPhone("5555-0000")
                .contactEmail("ventas@example.com")
                .requireAccountForCheckout(false)
                .guestTrackingEnabled(true)
                .allowedDeliveryMethods(List.of("delivery"))
                .allowedPaymentMethods(List.of("cash"))
                .build();
        config.setTenantId(tenant.getId());
        ecommerceConfigRepository.save(config);

        HeroBannerConfig banners = HeroBannerConfig.builder()
                .slides("""
                        [{"title":"Oferta de temporada","description":"Productos destacados","imageUrl":"https://cdn.example.com/banner.png"}]
                        """)
                .build();
        banners.setTenantId(tenant.getId());
        heroBannerConfigRepository.save(banners);
        return tenant;
    }

    private static String configUrl(String slug) {
        return BASE_URL + slug + "/config";
    }
}
