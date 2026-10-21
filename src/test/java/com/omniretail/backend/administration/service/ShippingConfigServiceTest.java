package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.dto.ShippingChannelConfigDto;
import com.omniretail.backend.administration.entity.ShippingChannel;
import com.omniretail.backend.administration.entity.ShippingConfig;
import com.omniretail.backend.administration.repository.ShippingConfigRepository;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShippingConfigServiceTest {

    @Mock ShippingConfigRepository repository;
    @Mock CurrentUser currentUser;
    @InjectMocks ShippingConfigService service;

    private final UUID tenant = UUID.randomUUID();
    private final ShippingChannelConfigDto flatWithThreshold =
            new ShippingChannelConfigDto(true, new BigDecimal("25.00"), new BigDecimal("300.00"));

    @Test
    void chargesFeeBelowThreshold() {
        assertThat(ShippingConfigService.calculate(flatWithThreshold, new BigDecimal("299.99")))
                .isEqualByComparingTo("25.00");
    }

    @Test
    void isFreeAtAndAboveThreshold() {
        assertThat(ShippingConfigService.calculate(flatWithThreshold, new BigDecimal("300.00")))
                .isEqualByComparingTo("0.00");
        assertThat(ShippingConfigService.calculate(flatWithThreshold, new BigDecimal("450.00")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void withoutThresholdAlwaysChargesFee() {
        var noThreshold = new ShippingChannelConfigDto(true, new BigDecimal("12.50"), null);

        assertThat(ShippingConfigService.calculate(noThreshold, new BigDecimal("10000.00")))
                .isEqualByComparingTo("12.50");
    }

    @Test
    void disabledChannelNeverCharges() {
        var disabled = new ShippingChannelConfigDto(false, new BigDecimal("25.00"), null);

        assertThat(ShippingConfigService.calculate(disabled, new BigDecimal("10.00")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void withoutSavedRowUsesChannelDefaults() {
        when(repository.findByTenantIdAndChannel(tenant, ShippingChannel.ecommerce)).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndChannel(tenant, ShippingChannel.pos)).thenReturn(Optional.empty());

        assertThat(service.calculate(tenant, ShippingChannel.ecommerce, new BigDecimal("100.00")))
                .isEqualByComparingTo("25.00");
        assertThat(service.calculate(tenant, ShippingChannel.pos, new BigDecimal("100.00")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void usesSavedChannelConfig() {
        ShippingConfig saved = ShippingConfig.builder()
                .channel(ShippingChannel.pos)
                .enabled(true)
                .deliveryFee(new BigDecimal("15.00"))
                .freeShippingThreshold(new BigDecimal("200.00"))
                .build();
        when(repository.findByTenantIdAndChannel(tenant, ShippingChannel.pos)).thenReturn(Optional.of(saved));

        assertThat(service.calculate(tenant, ShippingChannel.pos, new BigDecimal("150.00")))
                .isEqualByComparingTo("15.00");
        assertThat(service.calculate(tenant, ShippingChannel.pos, new BigDecimal("200.00")))
                .isEqualByComparingTo("0.00");
    }
}
