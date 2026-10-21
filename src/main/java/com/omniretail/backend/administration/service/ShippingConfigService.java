package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.SaveShippingConfigRequest;
import com.omniretail.backend.administration.dto.ShippingChannelConfigDto;
import com.omniretail.backend.administration.dto.ShippingConfigResponse;
import com.omniretail.backend.administration.entity.ShippingChannel;
import com.omniretail.backend.administration.entity.ShippingConfig;
import com.omniretail.backend.administration.repository.ShippingConfigRepository;
import com.omniretail.backend.shared.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Costo de envío a domicilio por canal. No exige la capacidad {@code ecommerce}: un negocio que solo
 * usa POS también cobra entregas a domicilio.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ShippingConfigService {

    // Sin fila para el canal se conserva el comportamiento previo a la configuración:
    // ecommerce Q25 con envío gratis desde Q300 y POS sin cobro de envío.
    public static final ShippingChannelConfigDto ECOMMERCE_DEFAULT =
            new ShippingChannelConfigDto(true, money(new BigDecimal("25")), money(new BigDecimal("300")));
    public static final ShippingChannelConfigDto POS_DEFAULT =
            new ShippingChannelConfigDto(false, money(BigDecimal.ZERO), null);

    private final ShippingConfigRepository configRepository;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public ShippingConfigResponse getConfig() {
        UUID tenantId = currentUser.require().tenantId();
        return new ShippingConfigResponse(
                channelConfig(tenantId, ShippingChannel.ecommerce),
                channelConfig(tenantId, ShippingChannel.pos));
    }

    public ShippingConfigResponse saveConfig(SaveShippingConfigRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        return new ShippingConfigResponse(
                save(tenantId, ShippingChannel.ecommerce, request.ecommerce()),
                save(tenantId, ShippingChannel.pos, request.pos()));
    }

    /** Tarifa vigente del canal, o la de por defecto si el negocio no la ha configurado. */
    @Transactional(readOnly = true)
    public ShippingChannelConfigDto channelConfig(UUID tenantId, ShippingChannel channel) {
        return configRepository.findByTenantIdAndChannel(tenantId, channel)
                .map(ShippingChannelConfigDto::from)
                .orElse(channel == ShippingChannel.ecommerce ? ECOMMERCE_DEFAULT : POS_DEFAULT);
    }

    /** Costo de envío a domicilio para un subtotal ya descontado. */
    @Transactional(readOnly = true)
    public BigDecimal calculate(UUID tenantId, ShippingChannel channel, BigDecimal subtotal) {
        return calculate(channelConfig(tenantId, channel), subtotal);
    }

    public static BigDecimal calculate(ShippingChannelConfigDto config, BigDecimal subtotal) {
        if (!config.enabled()) {
            return money(BigDecimal.ZERO);
        }
        BigDecimal threshold = config.freeShippingThreshold();
        if (threshold != null && subtotal.compareTo(threshold) >= 0) {
            return money(BigDecimal.ZERO);
        }
        return money(config.deliveryFee());
    }

    private ShippingChannelConfigDto save(
            UUID tenantId, ShippingChannel channel, ShippingChannelConfigDto input) {
        ShippingConfig config = configRepository.findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> {
                    ShippingConfig created = new ShippingConfig();
                    created.setTenantId(tenantId);
                    created.setChannel(channel);
                    return created;
                });
        config.setEnabled(input.enabled());
        config.setDeliveryFee(money(input.deliveryFee()));
        config.setFreeShippingThreshold(
                input.freeShippingThreshold() == null ? null : money(input.freeShippingThreshold()));
        return ShippingChannelConfigDto.from(configRepository.save(config));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
