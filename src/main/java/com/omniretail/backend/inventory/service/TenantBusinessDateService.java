package com.omniretail.backend.inventory.service;

import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.TenantRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantBusinessDateService {

    private static final ZoneId DEFAULT_BUSINESS_ZONE = ZoneId.of("America/Guatemala");

    private final TenantRepository tenantRepository;

    public LocalDate currentDate(UUID tenantId) {
        ZoneId zone = tenantRepository.findById(tenantId)
                .map(Tenant::getTimezone)
                .map(TenantBusinessDateService::zoneId)
                .orElse(DEFAULT_BUSINESS_ZONE);
        return LocalDate.now(zone);
    }

    private static ZoneId zoneId(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            return DEFAULT_BUSINESS_ZONE;
        }
    }
}
