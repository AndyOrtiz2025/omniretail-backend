package com.omniretail.backend.logistics.dto;

import com.omniretail.backend.logistics.entity.PickingAssignmentRelease;
import java.time.Instant;
import java.util.UUID;

public record PickingReleaseResponse(
        UUID id,
        UUID pickingOrderId,
        UUID actorUserId,
        String reason,
        Instant releasedAt) {

    public static PickingReleaseResponse from(PickingAssignmentRelease release) {
        return new PickingReleaseResponse(
                release.getId(),
                release.getPickingOrderId(),
                release.getActorUserId(),
                release.getReason(),
                release.getReleasedAt());
    }
}
