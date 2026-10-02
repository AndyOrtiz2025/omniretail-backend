package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.dto.InventoryMovementSummaryDto;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import org.springframework.data.jpa.domain.Specification;

public interface InventoryMovementSummaryRepository {

    InventoryMovementSummaryDto summarize(Specification<InventoryMovement> filters);
}
