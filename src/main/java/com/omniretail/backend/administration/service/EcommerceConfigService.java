package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.EcommerceConfigResponse;
import com.omniretail.backend.administration.dto.SaveEcommerceConfigRequest;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class EcommerceConfigService {

    // Politica fija de Web/App (ecommerceConfig.validation.ts): se fuerza sin importar lo que traiga el payload.
    private static final List<String> FIXED_DELIVERY_METHODS = List.of("home_delivery");
    private static final List<String> FIXED_PAYMENT_METHODS = List.of("card");

    private final EcommerceConfigRepository configRepository;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public EcommerceConfigResponse getConfig() {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);
        return configRepository
                .findByTenantId(tenantId)
                .map(EcommerceConfigResponse::from)
                .orElseGet(() -> defaultConfig(tenantId));
    }

    public EcommerceConfigResponse saveConfig(SaveEcommerceConfigRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        tenantCapabilityGuard.ensureTenantCapability(tenantId, SaasCapability.ecommerce);
        ensureDefaultBranch(tenantId, request.enabled(), request.defaultBranchId());

        EcommerceConfig config = configRepository.findByTenantId(tenantId).orElseGet(() -> {
            EcommerceConfig created = new EcommerceConfig();
            created.setTenantId(tenantId);
            return created;
        });
        config.setEnabled(request.enabled());
        config.setStoreName(request.storeName().trim());
        config.setLogoUrl(normalize(request.logoUrl()));
        config.setContactPhone(normalizePhone(request.contactPhone()));
        config.setContactEmail(normalizeEmail(request.contactEmail()));
        config.setRequireAccountForCheckout(request.requireAccountForCheckout());
        config.setGuestTrackingEnabled(request.guestTrackingEnabled());
        config.setAllowedDeliveryMethods(FIXED_DELIVERY_METHODS);
        config.setAllowedPaymentMethods(FIXED_PAYMENT_METHODS);
        config.setDefaultBranchId(request.defaultBranchId());

        return EcommerceConfigResponse.from(configRepository.save(config));
    }

    private void ensureDefaultBranch(UUID tenantId, boolean enabled, UUID defaultBranchId) {
        if (defaultBranchId == null) {
            if (enabled) {
                throw BusinessException.badRequest(
                        "Seleccione una sucursal predeterminada para habilitar el e-commerce.");
            }
            return;
        }
        Branch branch = branchRepository
                .findByTenantIdAndId(tenantId, defaultBranchId)
                .orElseThrow(() -> BusinessException.badRequest(
                        "La sucursal predeterminada no existe o no pertenece al negocio activo."));
        if (branch.getStatus() != BranchStatus.active) {
            throw BusinessException.badRequest("La sucursal predeterminada debe estar activa.");
        }
    }

    private EcommerceConfigResponse defaultConfig(UUID tenantId) {
        String storeName = tenantRepository.findById(tenantId).map(Tenant::getName).orElse("");
        return new EcommerceConfigResponse(
                tenantId,
                false,
                storeName,
                null,
                null,
                null,
                true,
                false,
                FIXED_DELIVERY_METHODS,
                FIXED_PAYMENT_METHODS,
                null);
    }

    // Mismo formato que normalizeGuatemalaPhone del frontend: "+502 0000-0000".
    private static String normalizePhone(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String digits = normalized.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("502")) {
            digits = digits.substring(3);
        }
        return "+502 " + digits.substring(0, 4) + "-" + digits.substring(4);
    }

    private static String normalizeEmail(String value) {
        String normalized = normalize(value);
        return normalized != null ? normalized.toLowerCase() : null;
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
