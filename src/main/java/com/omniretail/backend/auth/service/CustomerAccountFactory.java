package com.omniretail.backend.auth.service;

import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.pos.service.DocumentCounterService;
import com.omniretail.backend.shared.exception.BusinessException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Alta de un cliente: usuario, cliente y cuenta de acceso, en la transaccion de quien llama. La usan el
 * registro publico ({@link CustomerRegistrationService}) y el inicio de sesion con Google
 * ({@link GoogleLoginService}). Quien llama ya valido los datos y que el correo no exista en la tienda.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CustomerAccountFactory {

    private static final String USERS_TENANT_EMAIL_CONSTRAINT = "uk_users_tenant_email";
    private static final List<String> NON_CUSTOMER_PERMISSION_PREFIXES = List.of("admin.", "pos.", "inventory.");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CustomerRepository customerRepository;
    private final AuthAccountRepository authAccountRepository;
    private final DocumentCounterService documentCounterService;

    /** Correo ya usado en la tienda, detectado por la BD (alta concurrente con el mismo correo). */
    static final class EmailTakenException extends RuntimeException {
        EmailTakenException() {
            super(null, null, false, false);
        }
    }

    record CreatedCustomer(User user, AuthAccount account) {
    }

    /**
     * @param email ya normalizado (trim + minusculas).
     * @param phone opcional; null si no hay.
     * @throws EmailTakenException si otro alta concurrente uso el mismo correo en la tienda.
     */
    CreatedCustomer create(UUID tenantId, String name, String email, String phone, String passwordHash,
            AccountStatus status) {
        Role customerRole = findCustomerRole(tenantId);

        // El contador hace flush: se pide antes de crear las entidades.
        String code = documentCounterService.nextCustomerCode(tenantId);
        // customers.user_id tiene FK a users y no se puede actualizar: primero el usuario, luego el cliente.
        User newUser = User.builder()
                .name(name)
                .email(email)
                .phone(phone)
                .type(UserType.customer)
                .status(UserStatus.active)
                .roleId(customerRole.getId())
                .build();
        newUser.setTenantId(tenantId);
        User user = userRepository.save(newUser);
        Customer newCustomer = Customer.builder()
                .userId(user.getId())
                .code(code)
                .name(name)
                .email(email)
                .phone(phone)
                .status(CustomerStatus.active)
                .build();
        newCustomer.setTenantId(tenantId);
        Customer customer = customerRepository.save(newCustomer);
        user.setCustomerId(customer.getId());
        AuthAccount account = authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordHash)
                .status(status)
                .build());
        flushDetectingDuplicateEmail();
        return new CreatedCustomer(user, account);
    }

    /** Rol de sistema de cliente, buscado igual que el mock: nunca un rol con permisos operativos. */
    private Role findCustomerRole(UUID tenantId) {
        return roleRepository.findByTenantId(tenantId).stream()
                .filter(role -> Boolean.TRUE.equals(role.getIsSystem()))
                .filter(role -> role.getPermissions().contains("customer.account.read"))
                .filter(role -> role.getPermissions().stream()
                        .noneMatch(permission -> NON_CUSTOMER_PERMISSION_PREFIXES.stream().anyMatch(permission::startsWith)))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("El tenant {} no tiene el rol de sistema de cliente; no se puede registrar clientes.", tenantId);
                    return new BusinessException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                            CustomerRegistrationService.REGISTRATION_FAILED_MESSAGE);
                });
    }

    /** Un alta concurrente con el mismo correo la detecta la BD. */
    private void flushDetectingDuplicateEmail() {
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            String constraint = findCause(ex, ConstraintViolationException.class)
                    .map(ConstraintViolationException::getConstraintName)
                    .orElse(null);
            String sqlState = findCause(ex, SQLException.class).map(SQLException::getSQLState).orElse(null);
            if ("23505".equals(sqlState) && USERS_TENANT_EMAIL_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new EmailTakenException();
            }
            throw ex;
        }
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable ex, Class<T> type) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 20; depth++) {
            if (type.isInstance(cause)) {
                return Optional.of(type.cast(cause));
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }
}
