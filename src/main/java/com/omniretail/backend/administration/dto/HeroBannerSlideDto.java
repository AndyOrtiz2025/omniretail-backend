package com.omniretail.backend.administration.dto;

import jakarta.validation.constraints.Size;

public record HeroBannerSlideDto(
        @Size(max = 80) String title,
        @Size(max = 160) String description,
        @Size(max = 500) String imageUrl) {
}
