package com.omniretail.backend.administration.dto;

public record ProductTrackingDto(boolean stock, boolean lot, boolean expiration, boolean serial) {
}
