package com.omniretail.backend.shared.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Respuesta paginada. Misma forma que {@code PaginatedResult<T>} del frontend
 * ({@code src/core/types/pagination.types.ts}); {@code page} empieza en 1.
 */
public record PageResponse<T>(List<T> items, int page, int pageSize, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
