package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.inventory.dto.InventoryMovementSummaryDto;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

@RequiredArgsConstructor
public class InventoryMovementSummaryRepositoryImpl implements InventoryMovementSummaryRepository {

    private final EntityManager entityManager;

    @Override
    public InventoryMovementSummaryDto summarize(Specification<InventoryMovement> filters) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<InventoryMovement> movement = query.from(InventoryMovement.class);

        Expression<BigDecimal> incomingQuantity = cb.<BigDecimal>selectCase()
                .when(cb.equal(movement.get("type"), InventoryMovementType.in),
                        movement.<BigDecimal>get("quantity"))
                .otherwise(BigDecimal.ZERO);
        Expression<BigDecimal> outgoingQuantity = cb.<BigDecimal>selectCase()
                .when(cb.equal(movement.get("type"), InventoryMovementType.out),
                        movement.<BigDecimal>get("quantity"))
                .otherwise(BigDecimal.ZERO);

        query.multiselect(
                cb.coalesce(cb.sum(incomingQuantity), BigDecimal.ZERO),
                cb.coalesce(cb.sum(outgoingQuantity), BigDecimal.ZERO));
        Predicate predicate = filters.toPredicate(movement, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        Tuple totals = entityManager.createQuery(query).getSingleResult();
        BigDecimal incoming = totals.get(0, BigDecimal.class);
        BigDecimal outgoing = totals.get(1, BigDecimal.class);
        return new InventoryMovementSummaryDto(incoming, outgoing, incoming.subtract(outgoing));
    }
}
