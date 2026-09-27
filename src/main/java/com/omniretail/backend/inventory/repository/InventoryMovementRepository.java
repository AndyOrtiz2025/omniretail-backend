package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.entity.InventoryMovement;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {}
