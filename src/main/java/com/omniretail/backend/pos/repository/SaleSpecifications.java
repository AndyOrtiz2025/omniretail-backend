package com.omniretail.backend.pos.repository;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderSource;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.entity.Sale;
import com.omniretail.backend.pos.entity.SaleItem;
import com.omniretail.backend.pos.entity.SaleStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class SaleSpecifications {

    private SaleSpecifications() {}

    public static Specification<Sale> history(
            UUID tenantId,
            UUID branchId,
            String search,
            Instant fromInclusive,
            Instant toExclusive,
            SaleStatus status,
            DeliveryMethod deliveryMethod,
            OrderStatus operationalStatus) {
        return (sale, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(sale.get("tenantId"), tenantId));
            predicates.add(cb.equal(sale.get("branchId"), branchId));
            if (fromInclusive != null) {
                predicates.add(cb.greaterThanOrEqualTo(sale.get("createdAt"), fromInclusive));
            }
            if (toExclusive != null) {
                predicates.add(cb.lessThan(sale.get("createdAt"), toExclusive));
            }
            if (status != null) {
                predicates.add(cb.equal(sale.get("status"), status));
            }
            if (deliveryMethod == DeliveryMethod.immediate) {
                predicates.add(cb.isNull(sale.get("sourceOrderId")));
            } else if (deliveryMethod != null) {
                predicates.add(orderMatches(
                        query.subquery(Integer.class), sale, tenantId, deliveryMethod, null, null, cb));
            }
            if (operationalStatus != null) {
                predicates.add(orderMatches(
                        query.subquery(Integer.class), sale, tenantId, null, operationalStatus, null, cb));
            }

            String term = normalize(search);
            if (term != null) {
                String pattern = "%" + term + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(sale.get("number")), pattern),
                        cb.like(cb.lower(sale.get("documentLegalName")), pattern),
                        customerMatches(query.subquery(Integer.class), sale, tenantId, pattern, cb),
                        orderMatches(query.subquery(Integer.class), sale, tenantId, null, null, pattern, cb),
                        itemMatches(query.subquery(Integer.class), sale, pattern, cb)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Sale> status(SaleStatus status) {
        return (sale, query, cb) -> cb.equal(sale.get("status"), status);
    }

    private static Predicate customerMatches(
            Subquery<Integer> subquery,
            Root<Sale> sale,
            UUID tenantId,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<Customer> customer = subquery.from(Customer.class);
        subquery.select(cb.literal(1)).where(
                cb.equal(customer.get("tenantId"), tenantId),
                cb.equal(customer.get("id"), sale.get("customerId")),
                cb.like(cb.lower(customer.get("name")), pattern));
        return cb.exists(subquery);
    }

    private static Predicate orderMatches(
            Subquery<Integer> subquery,
            Root<Sale> sale,
            UUID tenantId,
            DeliveryMethod deliveryMethod,
            OrderStatus operationalStatus,
            String searchPattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<Order> order = subquery.from(Order.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(order.get("tenantId"), tenantId));
        predicates.add(cb.equal(order.get("branchId"), sale.get("branchId")));
        predicates.add(cb.equal(order.get("id"), sale.get("sourceOrderId")));
        predicates.add(cb.equal(order.get("source"), OrderSource.pos));
        if (deliveryMethod != null) {
            predicates.add(cb.equal(order.get("deliveryMethod"), deliveryMethod));
        }
        if (operationalStatus != null) {
            predicates.add(cb.equal(order.get("status"), operationalStatus));
        }
        if (searchPattern != null) {
            predicates.add(cb.or(
                    cb.like(cb.lower(order.get("orderNumber")), searchPattern),
                    cb.like(cb.lower(order.get("guestCustomer").cast(String.class)), searchPattern)));
        }
        subquery.select(cb.literal(1)).where(predicates.toArray(Predicate[]::new));
        return cb.exists(subquery);
    }

    private static Predicate itemMatches(
            Subquery<Integer> subquery,
            Root<Sale> sale,
            String pattern,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Root<SaleItem> item = subquery.from(SaleItem.class);
        subquery.select(cb.literal(1)).where(
                cb.equal(item.get("saleId"), sale.get("id")),
                cb.or(
                        cb.like(cb.lower(item.get("skuSnapshot")), pattern),
                        cb.like(cb.lower(item.get("nameSnapshot")), pattern)));
        return cb.exists(subquery);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
