package com.omniretail.backend.administration.service;

import com.omniretail.backend.administration.dto.BusinessConfigResponse;
import com.omniretail.backend.administration.dto.ProductTrackingDto;
import com.omniretail.backend.administration.dto.SaveBusinessConfigRequest;
import com.omniretail.backend.administration.entity.BusinessCapabilitiesConfig;
import com.omniretail.backend.administration.entity.BusinessPreset;
import com.omniretail.backend.administration.repository.BusinessCapabilitiesConfigRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class BusinessConfigService {

    private static final String INVENTORY_CAPABILITIES_REQUIRE_INVENTORY =
            "Las capacidades de inventario no pueden permanecer activas sin control de inventario.";
    private static final String TRACKING_REQUIRES_CAPABILITY =
            "La trazabilidad por defecto requiere activar sus capacidades relacionadas.";
    private static final String CAPABILITY_NOT_IN_PLAN =
            "Tu plan actual no incluye esta funcionalidad. Actualiza tu plan para habilitarla.";

    // Mismos valores que business-defaults.ts del frontend; si la config no coincide, el preset pasa a custom.
    private static final Map<BusinessPreset, Capabilities> PRESET_DEFAULTS = Map.of(
            BusinessPreset.hardware_store,
            new Capabilities(true, false, false, true, true, true, true, true, true,
                    new ProductTrackingDto(true, false, false, false)),
            BusinessPreset.pharmacy,
            new Capabilities(true, true, true, false, true, true, true, true, true,
                    new ProductTrackingDto(true, true, true, false)),
            BusinessPreset.grocery,
            new Capabilities(true, true, true, false, true, true, true, true, true,
                    new ProductTrackingDto(true, true, true, false)),
            BusinessPreset.services,
            new Capabilities(false, false, false, false, false, true, true, true, true,
                    new ProductTrackingDto(false, false, false, false)));

    private final BusinessCapabilitiesConfigRepository configRepository;
    private final TenantCapabilityGuard tenantCapabilityGuard;
    private final CurrentUser currentUser;

    @Transactional(readOnly = true)
    public BusinessConfigResponse getConfig() {
        UUID tenantId = currentUser.require().tenantId();
        return configRepository
                .findByTenantId(tenantId)
                .map(BusinessConfigResponse::from)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND,
                        "BUSINESS_CONFIG_NOT_FOUND",
                        "La configuración del negocio no está disponible."));
    }

    public BusinessConfigResponse saveConfig(SaveBusinessConfigRequest request) {
        UUID tenantId = currentUser.require().tenantId();
        Capabilities requested = Capabilities.from(request);
        ensureCoherent(requested);

        BusinessCapabilitiesConfig current = configRepository.findByTenantId(tenantId).orElse(null);
        ensureEntitledForNewlyEnabled(tenantId, current, requested);

        BusinessCapabilitiesConfig config = current;
        if (config == null) {
            config = new BusinessCapabilitiesConfig();
            config.setTenantId(tenantId);
        }
        config.setPreset(resolvePreset(request.preset(), requested));
        config.setSupportsInventory(requested.inventory());
        config.setSupportsLots(requested.lots());
        config.setSupportsExpiration(requested.expiration());
        config.setSupportsSerials(requested.serials());
        config.setSupportsMultipleLocations(requested.multipleLocations());
        config.setSupportsUnitsAndPackaging(requested.unitsAndPackaging());
        config.setSupportsProductAttributes(requested.productAttributes());
        config.setSupportsKits(requested.kits());
        config.setSupportsServices(requested.services());
        config.setAllowedPosPaymentMethods(request.allowedPosPaymentMethods());
        config.setTrackStock(requested.tracking().stock());
        config.setTrackLot(requested.tracking().lot());
        config.setTrackExpiration(requested.tracking().expiration());
        config.setTrackSerial(requested.tracking().serial());

        return BusinessConfigResponse.from(configRepository.save(config));
    }

    // Mismas reglas y prioridad que validateBusinessConfigDto del frontend. No se corrige en silencio:
    // la correccion automatica solo la hace el formulario antes de enviar.
    private static void ensureCoherent(Capabilities requested) {
        boolean hasInventoryCapabilities =
                requested.lots() || requested.expiration() || requested.serials() || requested.multipleLocations();
        if (!requested.inventory() && hasInventoryCapabilities) {
            throw BusinessException.badRequest(INVENTORY_CAPABILITIES_REQUIRE_INVENTORY);
        }

        ProductTrackingDto tracking = requested.tracking();
        boolean hasTracking = tracking.stock() || tracking.lot() || tracking.expiration() || tracking.serial();
        if ((!requested.inventory() && hasTracking)
                || (tracking.lot() && !requested.lots())
                || (tracking.expiration() && !requested.expiration())
                || (tracking.serial() && !requested.serials())) {
            throw BusinessException.badRequest(TRACKING_REQUIRES_CAPABILITY);
        }
    }

    // Solo el encendido (false -> true) exige entitlement; apagar siempre se permite para evitar lockout.
    private void ensureEntitledForNewlyEnabled(
            UUID tenantId, BusinessCapabilitiesConfig current, Capabilities requested) {
        boolean hasCurrent = current != null;
        if (requested.lots() && !(hasCurrent && current.isSupportsLots())) {
            tenantCapabilityGuard.ensureTenantCapability(
                    tenantId, SaasCapability.traceabilityLots, CAPABILITY_NOT_IN_PLAN);
        }
        if (requested.expiration() && !(hasCurrent && current.isSupportsExpiration())) {
            tenantCapabilityGuard.ensureTenantCapability(
                    tenantId, SaasCapability.traceabilityExpiration, CAPABILITY_NOT_IN_PLAN);
        }
        if (requested.serials() && !(hasCurrent && current.isSupportsSerials())) {
            tenantCapabilityGuard.ensureTenantCapability(
                    tenantId, SaasCapability.traceabilitySerials, CAPABILITY_NOT_IN_PLAN);
        }
        if (requested.kits() && !(hasCurrent && current.isSupportsKits())) {
            tenantCapabilityGuard.ensureTenantCapability(
                    tenantId, SaasCapability.catalogKits, CAPABILITY_NOT_IN_PLAN);
        }
    }

    private static BusinessPreset resolvePreset(BusinessPreset preset, Capabilities requested) {
        if (preset == BusinessPreset.custom) {
            return BusinessPreset.custom;
        }
        return requested.equals(PRESET_DEFAULTS.get(preset)) ? preset : BusinessPreset.custom;
    }

    private record Capabilities(
            boolean inventory,
            boolean lots,
            boolean expiration,
            boolean serials,
            boolean multipleLocations,
            boolean unitsAndPackaging,
            boolean productAttributes,
            boolean kits,
            boolean services,
            ProductTrackingDto tracking) {

        static Capabilities from(SaveBusinessConfigRequest request) {
            return new Capabilities(
                    request.supportsInventory(),
                    request.supportsLots(),
                    request.supportsExpiration(),
                    request.supportsSerials(),
                    request.supportsMultipleLocations(),
                    request.supportsUnitsAndPackaging(),
                    request.supportsProductAttributes(),
                    request.supportsKits(),
                    request.supportsServices(),
                    request.defaultProductTracking());
        }
    }
}
