package com.omniretail.backend.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.logistics.service.DispatchTestFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class LogisticsDispatchPersistenceTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void rejectsASecondCanonicalDispatchForTheSameSource() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        persistDispatch(fixture);

        assertThatThrownBy(() -> persistDispatch(fixture))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsReusingPackingForAnotherDispatch() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        persistDispatch(fixture);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatches
                    (id, tenant_id, branch_id, source_type, source_id, packing_id, status,
                     transport_mode, dispatched_by_user_id, dispatched_at)
                VALUES (?, ?, ?, 'transfer', ?, ?, 'dispatched', 'third_party', ?, now())
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), UUID.randomUUID(),
                fixture.packingId(), fixture.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateOperationIdWithinTenant() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        UUID dispatch = persistDispatch(fixture);
        persistOperation(fixture, dispatch, "operation-1");

        assertThatThrownBy(() -> persistOperation(fixture, dispatch, "operation-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicatePackageNumberWithinDispatch() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        UUID dispatch = persistDispatch(fixture);
        persistPackage(dispatch, "PKG-1");

        assertThatThrownBy(() -> persistPackage(dispatch, "PKG-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositePackingScopeAndOrderSourceCoherenceAreEnforced() {
        DispatchTestFixture.Data wrongScope = DispatchTestFixture.create(jdbc, 2);
        UUID otherBranch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Otra', 'store', 'active')
                """, otherBranch, wrongScope.tenantId(), "OTHER-" + otherBranch.toString().substring(0, 8));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatches
                    (id, tenant_id, branch_id, source_type, source_id, order_id, packing_id,
                     status, transport_mode, dispatched_by_user_id, dispatched_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, 'dispatched', 'third_party', ?, now())
                """, UUID.randomUUID(), wrongScope.tenantId(), otherBranch, wrongScope.orderId(),
                wrongScope.orderId(), wrongScope.packingId(), wrongScope.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderSourceMustMatchOrderId() {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatches
                    (id, tenant_id, branch_id, source_type, source_id, order_id, packing_id,
                     status, transport_mode, dispatched_by_user_id, dispatched_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, 'dispatched', 'third_party', ?, now())
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), UUID.randomUUID(),
                fixture.orderId(), fixture.packingId(), fixture.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseContainsDispatchTablesAndCriticalConstraints() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('dispatches', 'dispatch_packages', 'dispatch_operations')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM databasechangelog WHERE id = '041-logistics-dispatch'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname IN ('uk_dispatches_tenant_source', 'uk_dispatches_tenant_packing',
                                  'fk_dispatches_packing', 'ck_dispatches_source',
                                  'uk_dispatch_packages_number',
                                  'uk_dispatch_operations_tenant_operation',
                                  'fk_dispatch_operations_dispatch')
                """, Integer.class)).isEqualTo(7);
    }

    private UUID persistDispatch(DispatchTestFixture.Data fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dispatches
                    (id, tenant_id, branch_id, source_type, source_id, order_id, packing_id,
                     status, transport_mode, carrier_name, tracking_number,
                     dispatched_by_user_id, dispatched_at)
                VALUES (?, ?, ?, 'order', ?, ?, ?, 'dispatched', 'third_party',
                        'Carrier', 'TRACK', ?, now())
                """, id, fixture.tenantId(), fixture.branchId(), fixture.orderId(), fixture.orderId(),
                fixture.packingId(), fixture.userId());
        return id;
    }

    private void persistOperation(
            DispatchTestFixture.Data fixture, UUID dispatch, String operationId) {
        jdbc.update("""
                INSERT INTO dispatch_operations
                    (id, tenant_id, branch_id, dispatch_id, operation_id, fingerprint, result_dispatch)
                VALUES (?, ?, ?, ?, ?, 'fingerprint', '{}'::jsonb)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.branchId(), dispatch, operationId);
    }

    private void persistPackage(UUID dispatch, String number) {
        jdbc.update("""
                INSERT INTO dispatch_packages (id, dispatch_id, number, weight, description)
                VALUES (?, ?, ?, 1.000, 'Caja')
                """, UUID.randomUUID(), dispatch, number);
    }
}
