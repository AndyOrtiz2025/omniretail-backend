package com.omniretail.backend.administration.repository;

import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<User> findByTenantIdAndId(UUID tenantId, UUID id);

    /** SELECT ... FOR UPDATE: serializa invitaciones del empleado sin perder aislamiento por tenant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.tenantId = :tenantId and u.id = :id")
    Optional<User> findByTenantIdAndIdForUpdate(UUID tenantId, UUID id);

    List<User> findAllByTenantIdAndTypeAndIdIn(UUID tenantId, UserType type, Collection<UUID> ids);

    List<User> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    boolean existsByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);

    Page<User> findByTenantId(UUID tenantId, Pageable pageable);

    Page<User> findByTenantIdAndType(UUID tenantId, UserType type, Pageable pageable);

    Page<User> findByTenantIdAndStatus(UUID tenantId, UserStatus status, Pageable pageable);

    Page<User> findByTenantIdAndTypeAndStatus(
            UUID tenantId, UserType type, UserStatus status, Pageable pageable);

    boolean existsByEmailIgnoreCaseAndType(String email, UserType type);

    boolean existsByTenantIdAndEmployeeCodeIgnoreCase(UUID tenantId, String employeeCode);

    boolean existsByTenantIdAndEmployeeCodeIgnoreCaseAndIdNot(UUID tenantId, String employeeCode, UUID id);

    Optional<User> findByTenantIdAndEmployeeCode(UUID tenantId, String employeeCode);

    long countByTenantIdAndTypeAndStatusNot(UUID tenantId, UserType type, UserStatus status);
}
