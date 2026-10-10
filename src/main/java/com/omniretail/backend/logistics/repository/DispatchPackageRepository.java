package com.omniretail.backend.logistics.repository;
import com.omniretail.backend.logistics.entity.DispatchPackage;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchPackageRepository extends JpaRepository<DispatchPackage, UUID> {

    List<DispatchPackage> findByDispatchIdOrderByNumberAsc(UUID dispatchId);

    List<DispatchPackage> findByDispatchIdInOrderByDispatchIdAscNumberAsc(
            Collection<UUID> dispatchIds);
}
