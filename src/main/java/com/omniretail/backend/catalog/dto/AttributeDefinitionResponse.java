package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.AttributeDataType;
import com.omniretail.backend.catalog.entity.AttributeDefinition;
import com.omniretail.backend.catalog.entity.AttributeDefinitionStatus;
import java.time.Instant;
import java.util.UUID;

public record AttributeDefinitionResponse(
        UUID id,
        String code,
        String name,
        AttributeDataType dataType,
        AttributeDefinitionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static AttributeDefinitionResponse from(AttributeDefinition definition) {
        return new AttributeDefinitionResponse(
                definition.getId(),
                definition.getCode(),
                definition.getName(),
                definition.getDataType(),
                definition.getStatus(),
                definition.getCreatedAt(),
                definition.getUpdatedAt());
    }
}
