package com.omniretail.backend.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.inventory.dto.ApproveInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CancelInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.CreateInventoryTransferRequest;
import com.omniretail.backend.inventory.dto.RejectInventoryTransferRequest;
import com.omniretail.backend.inventory.entity.InventoryTransferRequestStatus;
import com.omniretail.backend.inventory.entity.InventoryTransferStatus;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class InventoryTransferControllerPermissionTest {

    @Test
    void everyTransferEndpointRequiresManagePermission() throws Exception {
        assertManagePermission(InventoryTransferRequestController.class.getDeclaredMethod(
                "create", CreateInventoryTransferRequest.class));
        assertManagePermission(InventoryTransferRequestController.class.getDeclaredMethod(
                "list",
                UUID.class,
                UUID.class,
                InventoryTransferRequestStatus.class,
                Pageable.class));
        assertManagePermission(InventoryTransferRequestController.class.getDeclaredMethod(
                "approve", UUID.class, ApproveInventoryTransferRequest.class));
        assertManagePermission(InventoryTransferRequestController.class.getDeclaredMethod(
                "reject", UUID.class, RejectInventoryTransferRequest.class));
        assertManagePermission(InventoryTransferRequestController.class.getDeclaredMethod(
                "cancel", UUID.class, CancelInventoryTransferRequest.class));
        assertManagePermission(InventoryTransferController.class.getDeclaredMethod(
                "list",
                UUID.class,
                UUID.class,
                InventoryTransferStatus.class,
                Pageable.class));
        assertManagePermission(InventoryTransferController.class.getDeclaredMethod(
                "detail", UUID.class));
        assertManagePermission(InventoryTransferController.class.getDeclaredMethod(
                "cancel", UUID.class, CancelInventoryTransferRequest.class));
    }

    private static void assertManagePermission(Method method) {
        assertThat(method.getAnnotation(RequirePermission.class))
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo("inventory.transfers.manage");
    }
}
