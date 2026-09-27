package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.BankAccount;
import com.omniretail.backend.administration.entity.BankAccountStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    Optional<BankAccount> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<BankAccount> findByTenantId(UUID tenantId, Pageable pageable);

    Page<BankAccount> findByTenantIdAndStatus(UUID tenantId, BankAccountStatus status, Pageable pageable);

    List<BankAccount> findByTenantIdAndStatus(UUID tenantId, BankAccountStatus status);

    boolean existsByTenantIdAndAccountNumber(UUID tenantId, String accountNumber);

    boolean existsByTenantIdAndAccountNumberAndIdNot(UUID tenantId, String accountNumber, UUID id);
}
