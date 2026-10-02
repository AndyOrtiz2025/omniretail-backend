package com.omniretail.backend.catalog.dto;

import jakarta.validation.constraints.Min;

public record ProductMediaUpdateRequest(String url, String altText, @Min(0) Integer sortOrder) { }
