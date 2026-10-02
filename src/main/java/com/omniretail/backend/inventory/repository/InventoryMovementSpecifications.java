package com.omniretail.backend.inventory.repository;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.catalog.entity.Location;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.inventory.dto.InventoryMovementDisplayType;
import com.omniretail.backend.inventory.entity.InventoryMovement;
import com.omniretail.backend.inventory.entity.InventoryMovementType;
import com.omniretail.backend.logistics.entity.Dispatch;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleReturn;
import com.omniretail.backend.purchasing.entity.GoodsReceipt;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class InventoryMovementSpecifications {

    private InventoryMovementSpecifications() {}

    public static Specification<InventoryMovement> filtered(
            UUID tenantId,
            UUID branchId,
            Collection<UUID> allowedBranchIds,
            UUID productId,
            InventoryMovementType type,
            Instant from,
            Instant to,
            String search,
            InventoryMovementDisplayType displayType) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (branchId != null) predicates.add(cb.equal(root.get("branchId"), branchId));
            else if (allowedBranchIds != null) predicates.add(root.get("branchId").in(allowedBranchIds));
            if (productId != null) predicates.add(cb.equal(root.get("productId"), productId));
            if (type != null) predicates.add(cb.equal(root.get("type"), type));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (displayType != null) predicates.add(displayPredicate(root, cb, displayType));

            String term = normalize(search);
            if (term != null) {
                String pattern = "%" + term + "%";
                List<Predicate> matches = new ArrayList<>();
                matches.add(like(cb.lower(root.get("reason")), pattern, cb));
                matches.add(like(cb.lower(root.get("referenceType")), pattern, cb));
                matches.add(like(root.get("referenceId").cast(String.class), pattern, cb));
                matches.add(relatedLike(query.subquery(Integer.class), root, Product.class,
                        "productId", List.of("name", "sku"), tenantId, pattern, cb));
                matches.add(relatedLike(query.subquery(Integer.class), root, Branch.class,
                        "branchId", List.of("name", "code"), tenantId, pattern, cb));
                matches.add(relatedLike(query.subquery(Integer.class), root, User.class,
                        "performedByUserId", List.of("name", "email", "employeeCode"), tenantId, pattern, cb));
                matches.add(relatedLike(query.subquery(Integer.class), root, Location.class,
                        "fromLocationId", List.of("name", "code"), tenantId, pattern, cb));
                matches.add(relatedLike(query.subquery(Integer.class), root, Location.class,
                        "toLocationId", List.of("name", "code"), tenantId, pattern, cb));
                matches.add(referenceLike(query.subquery(Integer.class), root, GoodsReceipt.class,
                        "goods_receipt", "number", tenantId, pattern, cb));
                matches.add(saleReferenceLike(query.subquery(Integer.class), root, tenantId, pattern, cb));
                matches.add(returnReferenceLike(query.subquery(Integer.class), root, tenantId, pattern, cb));
                matches.add(referenceLike(query.subquery(Integer.class), root, Dispatch.class,
                        "dispatch", "trackingNumber", tenantId, pattern, cb));
                predicates.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate displayPredicate(
            Root<InventoryMovement> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            InventoryMovementDisplayType displayType) {
        Expression<String> reference = root.get("referenceType");
        return switch (displayType) {
            case purchase_in -> cb.equal(cb.lower(reference), "goods_receipt");
            case sale -> reference.in(InventoryMovementDisplayType.SALE_REFERENCES);
            case return_ -> reference.in(InventoryMovementDisplayType.RETURN_REFERENCES);
            case void_ -> reference.in(InventoryMovementDisplayType.VOID_REFERENCES);
            case dispatch -> cb.equal(cb.lower(reference), "dispatch");
            case in -> genericType(root, cb, InventoryMovementType.in);
            case out -> genericType(root, cb, InventoryMovementType.out);
            case transfer -> genericType(root, cb, InventoryMovementType.transfer);
            case transfer_out, transfer_in, inventory_adjustment, shrinkage,
                    manual_in, manual_out, store_pickup, adjustment -> cb.disjunction();
        };
    }

    private static Predicate genericType(
            Root<InventoryMovement> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            InventoryMovementType type) {
        return cb.and(
                cb.equal(root.get("type"), type),
                cb.or(
                        cb.isNull(root.get("referenceType")),
                        cb.not(root.get("referenceType").in(InventoryMovementDisplayType.SPECIFIC_REFERENCES))));
    }

    private static <T> Predicate relatedLike(
            Subquery<Integer> subquery,
            Root<InventoryMovement> movement,
            Class<T> type,
            String movementIdField,
            List<String> fields,
            UUID tenantId,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<T> related = subquery.from(type);
        List<Predicate> values = fields.stream()
                .map(field -> like(cb.lower(related.get(field)), pattern, cb))
                .toList();
        subquery.select(cb.literal(1)).where(
                cb.equal(related.get("tenantId"), tenantId),
                cb.equal(related.get("id"), movement.get(movementIdField)),
                cb.or(values.toArray(Predicate[]::new)));
        return cb.exists(subquery);
    }

    private static <T> Predicate referenceLike(
            Subquery<Integer> subquery,
            Root<InventoryMovement> movement,
            Class<T> type,
            String referenceType,
            String labelField,
            UUID tenantId,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<T> reference = subquery.from(type);
        subquery.select(cb.literal(1)).where(
                cb.equal(cb.lower(movement.get("referenceType")), referenceType.toLowerCase(Locale.ROOT)),
                cb.equal(reference.get("tenantId"), tenantId),
                cb.equal(reference.get("id"), movement.get("referenceId")),
                like(cb.lower(reference.get(labelField)), pattern, cb));
        return cb.exists(subquery);
    }

    private static Predicate saleReferenceLike(
            Subquery<Integer> subquery,
            Root<InventoryMovement> movement,
            UUID tenantId,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<Sale> sale = subquery.from(Sale.class);
        Set<String> types = new java.util.HashSet<>(InventoryMovementDisplayType.SALE_REFERENCES);
        types.addAll(InventoryMovementDisplayType.VOID_REFERENCES);
        subquery.select(cb.literal(1)).where(
                movement.get("referenceType").in(types),
                cb.equal(sale.get("tenantId"), tenantId),
                cb.equal(sale.get("id"), movement.get("referenceId")),
                like(cb.lower(sale.get("number")), pattern, cb));
        return cb.exists(subquery);
    }

    private static Predicate returnReferenceLike(
            Subquery<Integer> subquery,
            Root<InventoryMovement> movement,
            UUID tenantId,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<SaleReturn> saleReturn = subquery.from(SaleReturn.class);
        Root<Sale> sale = subquery.from(Sale.class);
        subquery.select(cb.literal(1)).where(
                movement.get("referenceType").in(InventoryMovementDisplayType.RETURN_REFERENCES),
                cb.equal(saleReturn.get("tenantId"), tenantId),
                cb.equal(saleReturn.get("id"), movement.get("referenceId")),
                cb.equal(sale.get("tenantId"), tenantId),
                cb.equal(sale.get("id"), saleReturn.get("saleId")),
                like(cb.lower(sale.get("number")), pattern, cb));
        return cb.exists(subquery);
    }

    private static Predicate like(
            Expression<String> expression,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        return cb.like(expression, pattern);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
