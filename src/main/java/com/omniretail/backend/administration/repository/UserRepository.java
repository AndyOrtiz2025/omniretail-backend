package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<User> findByTenantIdAndId(UUID tenantId, UUID id);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    Page<User> findByTenantId(UUID tenantId, Pageable pageable);

    Page<User> findByTenantIdAndType(UUID tenantId, UserType type, Pageable pageable);

    Page<User> findByTenantIdAndStatus(UUID tenantId, UserStatus status, Pageable pageable);

    Page<User> findByTenantIdAndTypeAndStatus(
            UUID tenantId, UserType type, UserStatus status, Pageable pageable);

    boolean existsByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);

    boolean existsByTenantIdAndEmployeeCodeIgnoreCase(UUID tenantId, String employeeCode);

    boolean existsByTenantIdAndEmployeeCodeIgnoreCaseAndIdNot(UUID tenantId, String employeeCode, UUID id);

    Optional<User> findByTenantIdAndEmployeeCode(UUID tenantId, String employeeCode);
}
