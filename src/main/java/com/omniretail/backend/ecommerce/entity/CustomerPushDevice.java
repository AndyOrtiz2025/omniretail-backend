package com.omniretail.backend.ecommerce.entity;

import com.omniretail.backend.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "customer_push_devices")
@Getter
@Setter
public class CustomerPushDevice extends TenantScopedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "push_token", nullable = false, length = 255)
    private String pushToken;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
}
