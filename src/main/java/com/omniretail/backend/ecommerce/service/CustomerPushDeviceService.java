package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerPushDevice;
import com.omniretail.backend.ecommerce.repository.CustomerPushDeviceRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CustomerPushDeviceService {

    private final CustomerPushDeviceRepository repository;
    private final CurrentUser currentUser;
    private final CurrentCustomerResolver customerResolver;

    @Transactional
    public void register(String token, String platform) {
        AuthenticatedUser actor = currentUser.require();
        Customer customer = customerResolver.require(actor);

        String normalizedToken = validateToken(token);
        String normalizedPlatform = validatePlatform(platform);

        CustomerPushDevice device = repository
                .findByTenantIdAndPushToken(
                        actor.tenantId(),
                        normalizedToken
                )
                .orElseGet(CustomerPushDevice::new);

        device.setTenantId(actor.tenantId());
        device.setCustomerId(customer.getId());
        device.setPushToken(normalizedToken);
        device.setPlatform(normalizedPlatform);
        device.setEnabled(true);

        repository.save(device);
    }

    @Transactional
    public void unregister(String token) {
        AuthenticatedUser actor = currentUser.require();
        Customer customer = customerResolver.require(actor);

        repository.findByTenantIdAndPushToken(
                actor.tenantId(),
                validateToken(token)
        ).ifPresent(device -> {
            if (customer.getId().equals(device.getCustomerId())) {
                device.setEnabled(false);
                repository.save(device);
            }
        });
    }

    private String validateToken(String token) {
        if (token == null || token.isBlank()
                || token.length() > 255
                || !(token.startsWith("ExponentPushToken[")
                     || token.startsWith("ExpoPushToken["))) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PUSH_TOKEN",
                    "Token push inválido."
            );
        }

        return token.trim();
    }

    private String validatePlatform(String platform) {
        String value = platform == null
                ? ""
                : platform.trim().toLowerCase(Locale.ROOT);

        if (!value.equals("android") && !value.equals("ios")) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PUSH_PLATFORM",
                    "Plataforma no válida."
            );
        }

        return value;
    }
}
