package com.omniretail.backend.inventory.service;

import com.omniretail.backend.inventory.dto.InventoryPhysicalSelection;
import com.omniretail.backend.inventory.dto.InventoryTraceabilitySelection;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class InventoryPhysicalSelectionCodec {

    private static final TypeReference<List<InventoryPhysicalSelection>> TYPE =
            new TypeReference<>() {};

    private final JsonMapper jsonMapper;

    public InventoryPhysicalSelectionCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<InventoryPhysicalSelection> canonical(
            UUID locationId, List<InventoryTraceabilitySelection> selections) {
        if (selections == null || selections.isEmpty()) return List.of();
        return selections.stream()
                .map(selection -> {
                    if (selection == null || selection.quantity() == null) {
                        throw invalidHistory();
                    }
                    List<String> serials = selection.serialNumbers() == null
                            ? List.of()
                            : selection.serialNumbers().stream()
                                    .map(value -> value == null ? null : value.trim())
                                    .sorted(Comparator.nullsFirst(Comparator.naturalOrder()))
                                    .toList();
                    return new InventoryPhysicalSelection(
                            locationId,
                            selection.lotId(),
                            selection.quantity().setScale(3, RoundingMode.UNNECESSARY),
                            serials);
                })
                .sorted(Comparator.comparing(
                                InventoryPhysicalSelection::locationId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(
                                InventoryPhysicalSelection::lotId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(selection -> selection.serialNumbers().isEmpty()
                                ? ""
                                : Objects.toString(selection.serialNumbers().getFirst(), "")))
                .toList();
    }

    public String encode(List<InventoryPhysicalSelection> selections) {
        return selections == null || selections.isEmpty()
                ? null
                : jsonMapper.writeValueAsString(selections);
    }

    public List<InventoryPhysicalSelection> decode(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<InventoryPhysicalSelection> values = jsonMapper.readValue(json, TYPE);
            if (values == null || values.stream().anyMatch(Objects::isNull)) {
                throw invalidHistory();
            }
            return values;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidHistory();
        }
    }

    public List<InventoryTraceabilitySelection> withoutLocation(
            List<InventoryPhysicalSelection> selections, UUID expectedLocationId) {
        if (selections == null || selections.isEmpty()) return List.of();
        if (selections.stream().anyMatch(selection ->
                !Objects.equals(selection.locationId(), expectedLocationId))) {
            throw invalidHistory();
        }
        return selections.stream()
                .map(selection -> new InventoryTraceabilitySelection(
                        selection.lotId(), selection.quantity(), selection.serialNumbers()))
                .toList();
    }

    private static BusinessException invalidHistory() {
        return new BusinessException(
                HttpStatus.CONFLICT,
                "PICKING_TRACE_HISTORY_INCONSISTENT",
                "La seleccion fisica persistida del Picking es inconsistente.");
    }
}
