package com.omniretail.backend.ecommerce.service;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.CustomerStatus;
import com.omniretail.backend.ecommerce.repository.CustomerRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resuelve el Customer activo de la sesion (tenant y usuario salen del JWT, nunca de la request). Un
 * empleado recibe 403 aunque su rol tenga permisos {@code customer.*}: el autoservicio es solo de clientes.
 */
@Component
@RequiredArgsConstructor
public class CurrentCustomerResolver {

    private final CustomerRepository customerRepository;

    public Customer require(AuthenticatedUser actor) {
        if (actor.userType() != UserType.customer) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCOUNT_REQUIRED",
                    "Esta consulta solo está disponible para clientes.");
        }
        return customerRepository.findByTenantIdAndUserIdAndStatus(
                        actor.tenantId(), actor.userId(), CustomerStatus.active)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "CUSTOMER_ACCOUNT_REQUIRED",
                        "No se encontró una cuenta de cliente activa para la sesión."));
    }
}
