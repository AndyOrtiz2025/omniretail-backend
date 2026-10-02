package com.omniretail.backend.logistics.dto;
import com.omniretail.backend.ecommerce.entity.*; import com.omniretail.backend.logistics.entity.DispatchStatus; import java.time.Instant; import java.util.*;
public record DispatchResponse(UUID orderId, OrderStatus orderStatus, UUID dispatchId, DispatchStatus dispatchStatus, TransportMode transportMode, String carrierName, String trackingNumber, Instant dispatchedAt, List<DispatchPackageResponse> packages, boolean idempotent) {}
