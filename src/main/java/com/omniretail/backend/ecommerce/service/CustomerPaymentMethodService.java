package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.ecommerce.dto.CreatePaymentMethodRequest;
import com.omniretail.backend.ecommerce.dto.PaymentMethodResponse;
import com.omniretail.backend.ecommerce.dto.UpdatePaymentMethodRequest;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerPaymentMethod;
import com.omniretail.backend.ecommerce.repository.CustomerPaymentMethodRepository;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.pos.entity.PaymentMethod;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.exception.FieldValidationException;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.validation.UnknownFields;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Mis metodos de pago" del cliente autenticado (paymentMethodService.ts /
 * MockCustomerPaymentMethodRepository). Tenant y cliente salen del JWT; una tarjeta de otro cliente
 * responde 404, igual que una inexistente. Nunca se recibe ni se guarda el numero completo ni el CVV.
 *
 * <p>Invariante: si el cliente tiene tarjetas, exactamente una es la principal. Igual que en
 * {@link CustomerAddressService}, las operaciones que la tocan bloquean primero la fila del cliente
 * (FOR UPDATE); el indice unico parcial {@code uk_customer_payment_methods_default} es la red de seguridad.
 */
@Service
@RequiredArgsConstructor
public class CustomerPaymentMethodService {

    /** Mismo prefijo que mockTokenizeCard.ts: el token del proveedor es simulado. */
    static final String TOKEN_PREFIX = "pm_demo_";

    private final CurrentUser currentUser;
    private final CurrentCustomerResolver currentCustomerResolver;
    private final CustomerRepository customerRepository;
    private final CustomerPaymentMethodRepository paymentMethodRepository;
    private final PaymentMethodPolicy paymentMethodPolicy;

    /** La principal primero; el resto de la mas antigua a la mas reciente. */
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> list() {
        Customer customer = currentCustomerResolver.require(currentUser.require());
        return paymentMethodRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(
                        customer.getTenantId(), customer.getId())
                .stream()
                .sorted(Comparator.comparing((CustomerPaymentMethod method) -> !Boolean.TRUE.equals(method.getIsDefault())))
                .map(PaymentMethodResponse::from)
                .toList();
    }

    /** La primera tarjeta del cliente nace principal; las siguientes no. */
    @Transactional
    public PaymentMethodResponse create(CreatePaymentMethodRequest request) {
        Customer customer = lockCurrentCustomer();
        UnknownFields.reject(request.unknownFields());
        throwIfInvalid(paymentMethodPolicy.validateCreate(request));

        CustomerPaymentMethod method = new CustomerPaymentMethod();
        method.setTenantId(customer.getTenantId());
        method.setCustomerId(customer.getId());
        method.setType(PaymentMethod.card);
        method.setProviderPaymentMethodId(TOKEN_PREFIX + UUID.randomUUID());
        method.setBrand(request.brand().trim());
        method.setIssuingBank(request.issuingBank().trim());
        method.setLast4(request.last4().trim());
        method.setExpirationMonth(request.expirationMonth());
        method.setExpirationYear(request.expirationYear());
        method.setCardholderName(blankToNull(request.cardholderName()));
        method.setIsDefault(!paymentMethodRepository.existsByTenantIdAndCustomerId(
                customer.getTenantId(), customer.getId()));
        return PaymentMethodResponse.from(paymentMethodRepository.saveAndFlush(method));
    }

    /** Solo titular y vencimiento: la principal se cambia con {@link #setDefault(UUID)}. */
    @Transactional
    public PaymentMethodResponse update(UUID id, UpdatePaymentMethodRequest request) {
        Customer customer = currentCustomerResolver.require(currentUser.require());
        UnknownFields.reject(request.unknownFields());
        throwIfInvalid(paymentMethodPolicy.validateUpdate(
                request.expirationMonth(), request.expirationYear(), request.cardholderName()));

        CustomerPaymentMethod method = findOwned(customer, id);
        method.setExpirationMonth(request.expirationMonth());
        method.setExpirationYear(request.expirationYear());
        method.setCardholderName(blankToNull(request.cardholderName()));
        return PaymentMethodResponse.from(paymentMethodRepository.saveAndFlush(method));
    }

    /** Si se borra la principal y quedan otras, se promueve la mas antigua en la misma transaccion. */
    @Transactional
    public void delete(UUID id) {
        Customer customer = lockCurrentCustomer();
        CustomerPaymentMethod method = findOwned(customer, id);
        boolean wasDefault = Boolean.TRUE.equals(method.getIsDefault());
        paymentMethodRepository.delete(method);
        paymentMethodRepository.flush();

        if (wasDefault) {
            paymentMethodRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(
                            customer.getTenantId(), customer.getId())
                    .stream()
                    .findFirst()
                    .ifPresent(oldest -> oldest.setIsDefault(true));
        }
    }

    @Transactional
    public PaymentMethodResponse setDefault(UUID id) {
        Customer customer = lockCurrentCustomer();
        CustomerPaymentMethod selected = findOwned(customer, id);

        // Primero se apaga la anterior y se escribe: el indice unico no admite dos principales ni un instante.
        paymentMethodRepository.findByTenantIdAndCustomerIdOrderByCreatedAtAscIdAsc(
                        customer.getTenantId(), customer.getId())
                .stream()
                .filter(method -> !method.getId().equals(selected.getId()))
                .filter(method -> Boolean.TRUE.equals(method.getIsDefault()))
                .forEach(method -> method.setIsDefault(false));
        paymentMethodRepository.flush();

        selected.setIsDefault(true);
        return PaymentMethodResponse.from(paymentMethodRepository.saveAndFlush(selected));
    }

    private Customer lockCurrentCustomer() {
        Customer customer = currentCustomerResolver.require(currentUser.require());
        return customerRepository.findByTenantIdAndIdForUpdate(customer.getTenantId(), customer.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCOUNT_REQUIRED",
                        "No se encontró una cuenta de cliente activa para la sesión."));
    }

    private CustomerPaymentMethod findOwned(Customer customer, UUID id) {
        return paymentMethodRepository.findByTenantIdAndCustomerIdAndId(customer.getTenantId(), customer.getId(), id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_METHOD_NOT_FOUND",
                        "Método de pago no encontrado."));
    }

    private static void throwIfInvalid(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
