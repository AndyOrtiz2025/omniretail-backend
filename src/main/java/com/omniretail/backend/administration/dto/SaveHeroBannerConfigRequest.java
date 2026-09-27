package com.omniretail.backend.administration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveHeroBannerConfigRequest(
        @NotNull
                @Size(min = 3, max = 3, message = "El carrusel debe tener exactamente 3 diapositivas.")
                List<@NotNull @Valid HeroBannerSlideDto> slides) {
}
