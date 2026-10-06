package com.omniretail.backend.auth.repository;

import com.omniretail.backend.auth.entity.AuthExternalIdentity;
import com.omniretail.backend.auth.entity.ExternalIdentityProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthExternalIdentityRepository extends JpaRepository<AuthExternalIdentity, UUID> {

    Optional<AuthExternalIdentity> findByProviderAndSubjectAndTenantId(
            ExternalIdentityProvider provider, String subject, UUID tenantId);

    Optional<AuthExternalIdentity> findByAccountIdAndProvider(UUID accountId, ExternalIdentityProvider provider);
}
