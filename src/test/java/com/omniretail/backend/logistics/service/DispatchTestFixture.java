package com.omniretail.backend.logistics.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DispatchTestFixture {

    private DispatchTestFixture() {}

    public static Data create(JdbcTemplate jdbc, int packageCount) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID balance = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID picking = UUID.randomUUID();
        UUID packing = UUID.randomUUID();
        String suffix = tenant.toString();

        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Dispatch test', ?)",
                tenant, "dispatch-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant, "BR-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Despachador', ?, 'employee', 'active', ?)
                """, user, tenant, user + "@test.local", branch);
        jdbc.update("""
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, 'Dispatch', ?, 'active')
                """, category, tenant, "dispatch-" + category);
        jdbc.update("""
                INSERT INTO units
                    (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
                """, unit, tenant, "U-" + unit.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Producto Dispatch', ?, ?)
                """, product, tenant, "SKU-" + product, category, unit);
        jdbc.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Bodega', 'warehouse', 'active')
                """, location, tenant, branch, "LOC-" + location.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, 10.000, 5.000)
                """, balance, tenant, branch, product, location);
        jdbc.update("""
                INSERT INTO orders
                    (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                     delivery_method, transport_mode, subtotal, discount_total, shipping_total,
                     total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{"name":"Cliente"}'::jsonb,
                        'ready_for_dispatch', 'home_delivery', 'third_party', 50, 0, 0, 50, ?)
                """, order, tenant, branch, "WEB-" + order, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku_snapshot, name_snapshot, quantity,
                     inventory_quantity, unit_price, discount, subtotal)
                VALUES (?, ?, ?, 'SKU', 'Producto Dispatch', 5.000,
                        5.000, 10.00, 0.00, 50.00)
                """, item, order, product);
        jdbc.update("""
                INSERT INTO inventory_reservations
                    (id, tenant_id, branch_id, source_type, source_id, source_line_id,
                     order_id, order_item_id, product_id, quantity, status, allocations)
                VALUES (?, ?, ?, 'order', ?, ?, ?, ?, ?, 5.000, 'active', ?::jsonb)
                """, reservation, tenant, branch, order, item, order, item, product,
                "[{\"id\":\"" + UUID.randomUUID() + "\",\"balanceId\":\"" + balance
                        + "\",\"locationId\":\"" + location
                        + "\",\"reservedQuantity\":5.000,\"consumedQuantity\":0.000}]");
        jdbc.update("""
                INSERT INTO picking_orders
                    (id, tenant_id, branch_id, source_type, source_id, order_id, status,
                     priority, completed_at)
                VALUES (?, ?, ?, 'order', ?, ?, 'completed', 'normal', now())
                """, picking, tenant, branch, order, order);
        jdbc.update("""
                INSERT INTO packings
                    (id, tenant_id, branch_id, source_type, source_id, order_id, picking_order_id,
                     status, package_protection_checked, document_included_checked,
                     recipient_verified_checked, total_weight, package_count,
                     label_generation_id, label_code, label_generated_at,
                     started_by_user_id, started_at, finalized_by_user_id, finalized_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, 'finalized', true, true, true,
                        4.500, ?, ?, ?, now(), ?, now(), ?, now())
                """, packing, tenant, branch, order, order, picking, packageCount,
                "label-generation-" + packing, "LBL-" + packing.toString().substring(0, 8),
                user, user);
        return new Data(tenant, branch, user, product, location, balance, order, item,
                reservation, packing, packageCount, "LBL-" + packing.toString().substring(0, 8));
    }

    public record Data(
            UUID tenantId,
            UUID branchId,
            UUID userId,
            UUID productId,
            UUID locationId,
            UUID balanceId,
            UUID orderId,
            UUID orderItemId,
            UUID reservationId,
            UUID packingId,
            int packageCount,
            String labelCode) {}
}
