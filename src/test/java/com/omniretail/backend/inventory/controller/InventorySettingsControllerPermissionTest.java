package com.omniretail.backend.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.omniretail.backend.inventory.dto.InventoryAlertStatus;
import com.omniretail.backend.inventory.dto.UpdateInventorySettingsRequest;
import com.omniretail.backend.shared.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class InventorySettingsControllerPermissionTest {

    @Test
    void readsUseInventoryStockReadAndWritesUseCatalogProductUpdate() throws Exception {
        assertPermission(
                InventorySettingsController.class.getDeclaredMethod(
                        "list", UUID.class, UUID.class, Pageable.class),
                "inventory.stock.read");
        assertPermission(
                InventorySettingsController.class.getDeclaredMethod(
                        "get", UUID.class, UUID.class),
                "inventory.stock.read");
        assertPermission(
                InventorySettingsController.class.getDeclaredMethod(
                        "upsert", UUID.class, UUID.class, UpdateInventorySettingsRequest.class),
                "catalog.products.update");
        assertPermission(
                InventoryAlertController.class.getDeclaredMethod(
                        "list", UUID.class, InventoryAlertStatus.class, Pageable.class),
                "inventory.stock.read");
        assertPermission(
                InventoryTraceabilityController.class.getDeclaredMethod(
                        "availableLots", UUID.class, UUID.class, UUID.class),
                "inventory.stock.read");
        assertPermission(
                InventoryTraceabilityController.class.getDeclaredMethod(
                        "availableSerials", UUID.class, UUID.class, UUID.class, UUID.class),
                "inventory.stock.read");
    }

    private static void assertPermission(Method method, String expected) {
        assertThat(method.getAnnotation(RequirePermission.class))
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo(expected);
    }
}
