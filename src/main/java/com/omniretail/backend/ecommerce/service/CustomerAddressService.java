package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.dto.AddressRequest;
import com.omniretail.backend.ecommerce.dto.AddressResponse;
import com.omniretail.backend.ecommerce.entity.Address;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.repository.AddressRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.validation.UnknownFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Mis direcciones" del cliente autenticado (addressService.ts / MockAddressRepository). Tenant y
 * cliente salen del JWT; una direccion de otro cliente responde 404, igual que una inexistente.
 *
 * <p>Invariante: si el cliente tiene direcciones, exactamente una es la predeterminada. Las operaciones
 * que la tocan bloquean primero la fila del cliente (FOR UPDATE), asi que se ejecutan de a una por
 * cliente; el indice unico parcial {@code uk_addresses_customer_default} es la red de seguridad.
 */
@Service
@RequiredArgsConstructor
public class CustomerAddressService {

    /** La plataforma opera solo en Guatemala: el pais nunca lo decide el cliente. */
    static final String PLATFORM_COUNTRY = "Guatemala";

    private final CurrentUser currentUser;
    private final CurrentCustomerResolver currentCustomerResolver;
    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;
    private final DeliveryAddressPolicy deliveryAddressPolicy;

    @Transactional(readOnly = true)
    public List<AddressResponse> list() {
        Customer customer = currentCustomerResolver.require(currentUser.require());
        return addressRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(
                        customer.getTenantId(), customer.getId())
                .stream()
                .map(AddressResponse::from)
                .toList();
    }

    /** La primera direccion del cliente nace predeterminada; las siguientes no. */
    @Transactional
    public AddressResponse create(AddressRequest request) {
        Customer customer = lockCurrentCustomer();
        validate(request);

        Address address = new Address();
        address.setTenantId(customer.getTenantId());
        address.setCustomerId(customer.getId());
        applyFields(address, request);
        address.setIsDefault(!addressRepository.existsByTenantIdAndCustomerId(customer.getTenantId(), customer.getId()));
        return AddressResponse.from(addressRepository.saveAndFlush(address));
    }

    /** Solo cambia los datos: la predeterminada se cambia con {@link #setDefault(UUID)}. */
    @Transactional
    public AddressResponse update(UUID id, AddressRequest request) {
        Customer customer = currentCustomerResolver.require(currentUser.require());
        validate(request);

        Address address = findOwned(customer, id);
        applyFields(address, request);
        return AddressResponse.from(addressRepository.saveAndFlush(address));
    }

    /** Si se borra la predeterminada y quedan otras, se promueve la mas antigua en la misma transaccion. */
    @Transactional
    public void delete(UUID id) {
        Customer customer = lockCurrentCustomer();
        Address address = findOwned(customer, id);
        boolean wasDefault = Boolean.TRUE.equals(address.getIsDefault());
        addressRepository.delete(address);
        addressRepository.flush();

        if (wasDefault) {
            addressRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(customer.getTenantId(), customer.getId())
                    .stream()
                    .findFirst()
                    .ifPresent(oldest -> oldest.setIsDefault(true));
        }
    }

    @Transactional
    public AddressResponse setDefault(UUID id) {
        Customer customer = lockCurrentCustomer();
        Address selected = findOwned(customer, id);

        // Primero se apaga la anterior y se escribe: el indice unico no admite dos predeterminadas ni un instante.
        addressRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(customer.getTenantId(), customer.getId())
                .stream()
                .filter(address -> !address.getId().equals(selected.getId()))
                .filter(address -> Boolean.TRUE.equals(address.getIsDefault()))
                .forEach(address -> address.setIsDefault(false));
        addressRepository.flush();

        selected.setIsDefault(true);
        return AddressResponse.from(addressRepository.saveAndFlush(selected));
    }

    private Customer lockCurrentCustomer() {
        Customer customer = currentCustomerResolver.require(currentUser.require());
        return customerRepository.findByTenantIdAndIdForUpdate(customer.getTenantId(), customer.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCOUNT_REQUIRED",
                        "No se encontró una cuenta de cliente activa para la sesión."));
    }

    private Address findOwned(Customer customer, UUID id) {
        return addressRepository.findByTenantIdAndCustomerIdAndId(customer.getTenantId(), customer.getId(), id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND",
                        "Dirección no encontrada."));
    }

    private void validate(AddressRequest request) {
        UnknownFields.reject(request.unknownFields());
        Map<String, String> errors = deliveryAddressPolicy.validate(request);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    /** Mismo recorte que addressService.toFields: los opcionales vacios se guardan como null. */
    private static void applyFields(Address address, AddressRequest request) {
        address.setLabel(request.label().trim());
        address.setRecipientName(request.recipientName().trim());
        address.setLine1(request.line1().trim());
        address.setLine2(blankToNull(request.line2()));
        address.setCity(request.city().trim());
        address.setStateOrDepartment(request.stateOrDepartment().trim());
        address.setPostalCode(blankToNull(request.postalCode()));
        address.setCountry(PLATFORM_COUNTRY);
        address.setReferences(blankToNull(request.references()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
