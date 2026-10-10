package com.omniretail.backend.logistics.repository;

import com.omniretail.backend.ecommerce.entity.Customer;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.inventory.entity.InventoryTransfer;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.logistics.dto.LogisticsHistoryDeliveryMethod;
import com.omniretail.backend.logistics.entity.PickingOrder;
import com.omniretail.backend.logistics.entity.PickingSourceType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class LogisticsHistorySpecifications {

    private LogisticsHistorySpecifications() {}

    public static Specification<PickingOrder> history(
            UUID tenantId,
            UUID branchId,
            String search,
            OrderStatus orderStatus,
            InventoryTransferStatus transferStatus,
            boolean statusSpecified,
            LogisticsHistoryDeliveryMethod deliveryMethod,
            Instant fromInclusive,
            Instant toExclusive) {
        return (picking, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(picking.get("tenantId"), tenantId));
            predicates.add(cb.equal(picking.get("branchId"), branchId));
            if (fromInclusive != null) {
                predicates.add(cb.greaterThanOrEqualTo(picking.get("createdAt"), fromInclusive));
            }
            if (toExclusive != null) {
                predicates.add(cb.lessThan(picking.get("createdAt"), toExclusive));
            }

            String pattern = normalize(search);
            if (pattern != null) {
                pattern = "%" + pattern + "%";
            }
            List<Predicate> sources = new ArrayList<>();
            sources.add(orderSourceMatches(
                    query,
                    cb,
                    picking,
                    tenantId,
                    branchId,
                    pattern,
                    orderStatus,
                    statusSpecified,
                    deliveryMethod));
            if (pattern != null) {
                sources.add(orderCustomerSourceMatches(
                        query,
                        cb,
                        picking,
                        tenantId,
                        branchId,
                        pattern,
                        orderStatus,
                        statusSpecified,
                        deliveryMethod));
            }
            sources.add(transferSourceMatches(
                    query,
                    cb,
                    picking,
                    tenantId,
                    branchId,
                    pattern,
                    transferStatus,
                    statusSpecified,
                    deliveryMethod));
            predicates.add(cb.or(sources.toArray(Predicate[]::new)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate orderSourceMatches(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<PickingOrder> picking,
            UUID tenantId,
            UUID branchId,
            String searchPattern,
            OrderStatus status,
            boolean statusSpecified,
            LogisticsHistoryDeliveryMethod deliveryMethod) {
        if ((statusSpecified && status == null)
                || deliveryMethod == LogisticsHistoryDeliveryMethod.transfer) {
            return cb.disjunction();
        }
        Subquery<Integer> sourceQuery = query.subquery(Integer.class);
        Root<Order> order = sourceQuery.from(Order.class);
        List<Predicate> source = new ArrayList<>();
        source.add(cb.equal(picking.get("sourceType"), PickingSourceType.order));
        source.add(cb.equal(order.get("tenantId"), tenantId));
        source.add(cb.equal(order.get("branchId"), branchId));
        source.add(cb.equal(order.get("id"), picking.get("sourceId")));
        if (status != null) {
            source.add(cb.equal(order.get("status"), status));
        }
        if (deliveryMethod != null) {
            source.add(cb.equal(
                    order.get("deliveryMethod"), DeliveryMethod.valueOf(deliveryMethod.name())));
        }
        if (searchPattern != null) {
            source.add(cb.or(
                    cb.like(cb.lower(order.get("orderNumber")), searchPattern),
                    cb.like(cb.lower(order.get("guestCustomer").cast(String.class)), searchPattern),
                    cb.like(cb.lower(order.get("storePickupContact").cast(String.class)), searchPattern),
                    cb.like(cb.lower(order.get("deliveryAddress").cast(String.class)), searchPattern)));
        }
        sourceQuery.select(cb.literal(1)).where(source.toArray(Predicate[]::new));
        return cb.exists(sourceQuery);
    }

    private static Predicate transferSourceMatches(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<PickingOrder> picking,
            UUID tenantId,
            UUID branchId,
            String searchPattern,
            InventoryTransferStatus status,
            boolean statusSpecified,
            LogisticsHistoryDeliveryMethod deliveryMethod) {
        if ((statusSpecified && status == null)
                || (deliveryMethod != null
                        && deliveryMethod != LogisticsHistoryDeliveryMethod.transfer)) {
            return cb.disjunction();
        }
        Subquery<Integer> sourceQuery = query.subquery(Integer.class);
        Root<InventoryTransfer> transfer = sourceQuery.from(InventoryTransfer.class);
        List<Predicate> source = new ArrayList<>();
        source.add(cb.equal(picking.get("sourceType"), PickingSourceType.transfer));
        source.add(cb.equal(transfer.get("tenantId"), tenantId));
        source.add(cb.equal(transfer.get("sourceBranchId"), branchId));
        source.add(cb.equal(transfer.get("id"), picking.get("sourceId")));
        if (status != null) {
            source.add(cb.equal(transfer.get("status"), status));
        }
        if (searchPattern != null) {
            source.add(cb.like(cb.lower(transfer.get("number")), searchPattern));
        }
        sourceQuery.select(cb.literal(1)).where(source.toArray(Predicate[]::new));
        return cb.exists(sourceQuery);
    }

    private static Predicate orderCustomerSourceMatches(
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            Root<PickingOrder> picking,
            UUID tenantId,
            UUID branchId,
            String pattern,
            OrderStatus status,
            boolean statusSpecified,
            LogisticsHistoryDeliveryMethod deliveryMethod) {
        if ((statusSpecified && status == null)
                || deliveryMethod == LogisticsHistoryDeliveryMethod.transfer) {
            return cb.disjunction();
        }
        Subquery<Integer> customerQuery = query.subquery(Integer.class);
        Root<Customer> customer = customerQuery.from(Customer.class);
        Root<Order> order = customerQuery.from(Order.class);
        List<Predicate> source = new ArrayList<>();
        source.add(cb.equal(picking.get("sourceType"), PickingSourceType.order));
        source.add(cb.equal(order.get("tenantId"), tenantId));
        source.add(cb.equal(order.get("branchId"), branchId));
        source.add(cb.equal(order.get("id"), picking.get("sourceId")));
        source.add(cb.equal(customer.get("tenantId"), tenantId));
        source.add(cb.equal(customer.get("id"), order.get("customerId")));
        source.add(cb.like(cb.lower(customer.get("name")), pattern));
        if (status != null) {
            source.add(cb.equal(order.get("status"), status));
        }
        if (deliveryMethod != null) {
            source.add(cb.equal(
                    order.get("deliveryMethod"), DeliveryMethod.valueOf(deliveryMethod.name())));
        }
        customerQuery.select(cb.literal(1)).where(source.toArray(Predicate[]::new));
        return cb.exists(customerQuery);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
