package com.omniretail.backend.catalog.dto;

import com.omniretail.backend.catalog.entity.AttributeDataType;
import com.omniretail.backend.catalog.entity.AttributeDefinitionStatus;
import java.util.UUID;

public record ProductAttributeValueResponse(
        UUID attributeId,
        String code,
        String name,
        AttributeDataType dataType,
        AttributeDefinitionStatus status,
        String value) {}
