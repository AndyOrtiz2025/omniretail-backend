package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerPushDevice;
import com.omniretail.backend.ecommerce.repository.CustomerPushDeviceRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerPushDeviceServiceTest {

    private CustomerPushDeviceRepository repository;
    private CurrentUser currentUser;
    private CurrentCustomerResolver customerResolver;
    private CustomerPushDeviceService service;

    private UUID tenantId;
    private UUID customerId;
    private Customer customer;

    @BeforeEach
    void setUp() {
        repository = mock(CustomerPushDeviceRepository.class);
        currentUser = mock(CurrentUser.class);
        customerResolver = mock(CurrentCustomerResolver.class);

        service = new CustomerPushDeviceService(
                repository, currentUser, customerResolver
        );

        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();

        AuthenticatedUser actor = new AuthenticatedUser(
                UUID.randomUUID(),
                tenantId,
                UserType.customer,
                null,
                null,
                null
        );

        customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);

        when(currentUser.require()).thenReturn(actor);
        when(customerResolver.require(actor)).thenReturn(customer);
    }

    @Test
    void registersAndroidDevice() {
        String token = "ExponentPushToken[abc123]";

        when(repository.findByTenantIdAndPushToken(tenantId, token))
                .thenReturn(Optional.empty());

        service.register(token, "ANDROID");

        ArgumentCaptor<CustomerPushDevice> captor =
                ArgumentCaptor.forClass(CustomerPushDevice.class);

        verify(repository).save(captor.capture());

        CustomerPushDevice saved = captor.getValue();

        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getCustomerId()).isEqualTo(customerId);
        assertThat(saved.getPushToken()).isEqualTo(token);
        assertThat(saved.getPlatform()).isEqualTo("android");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void reusesPreviouslyRegisteredDevice() {
        String token = "ExpoPushToken[existing]";
        CustomerPushDevice existing = new CustomerPushDevice();
        existing.setEnabled(false);

        when(repository.findByTenantIdAndPushToken(tenantId, token))
                .thenReturn(Optional.of(existing));

        service.register(token, "ios");

        verify(repository).save(existing);

        assertThat(existing.getCustomerId()).isEqualTo(customerId);
        assertThat(existing.getPlatform()).isEqualTo("ios");
        assertThat(existing.isEnabled()).isTrue();
    }

    @Test
    void rejectsInvalidPushToken() {
        assertThatThrownBy(() -> service.register("invalid", "android"))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNullPushToken() {
        assertThatThrownBy(() -> service.register(null, "android"))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsInvalidPlatform() {
        assertThatThrownBy(() ->
                service.register("ExpoPushToken[valid]", "windows"))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void unregistersOwnedDevice() {
        String token = "ExpoPushToken[owned]";
        CustomerPushDevice device = new CustomerPushDevice();
        device.setCustomerId(customerId);
        device.setEnabled(true);

        when(repository.findByTenantIdAndPushToken(tenantId, token))
                .thenReturn(Optional.of(device));

        service.unregister(token);

        assertThat(device.isEnabled()).isFalse();
        verify(repository).save(device);
    }

    @Test
    void doesNotUnregisterAnotherCustomersDevice() {
        String token = "ExpoPushToken[other]";
        CustomerPushDevice device = new CustomerPushDevice();
        device.setCustomerId(UUID.randomUUID());
        device.setEnabled(true);

        when(repository.findByTenantIdAndPushToken(tenantId, token))
                .thenReturn(Optional.of(device));

        service.unregister(token);

        assertThat(device.isEnabled()).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    void unregisteringUnknownDeviceDoesNothing() {
        String token = "ExpoPushToken[unknown]";

        when(repository.findByTenantIdAndPushToken(tenantId, token))
                .thenReturn(Optional.empty());

        service.unregister(token);

        verify(repository, never()).save(any());
    }
}
