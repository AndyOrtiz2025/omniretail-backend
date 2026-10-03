package com.omniretail.backend.ecommerce.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Departamentos y municipios de Guatemala, copiados de {@code src/config/guatemala-locations.ts} del
 * frontend a {@code geo/guatemala-locations.json}. Si el frontend cambia la lista, se actualiza ese JSON.
 */
@Component
public class GuatemalaLocations {

    private static final String RESOURCE = "geo/guatemala-locations.json";

    private final Map<String, List<String>> municipalitiesByDepartment;

    public GuatemalaLocations(JsonMapper jsonMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            LocationsFile file = jsonMapper.readValue(input, LocationsFile.class);
            Map<String, List<String>> map = new LinkedHashMap<>();
            file.departments().forEach(department -> map.put(department.name(), List.copyOf(department.municipalities())));
            this.municipalitiesByDepartment = Map.copyOf(map);
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer " + RESOURCE, ex);
        }
    }

    public boolean isDepartment(String department) {
        return department != null && municipalitiesByDepartment.containsKey(department);
    }

    public boolean isMunicipalityOf(String department, String municipality) {
        return isDepartment(department) && municipalitiesByDepartment.get(department).contains(municipality);
    }

    private record LocationsFile(List<Department> departments) {
    }

    private record Department(String name, List<String> municipalities) {
    }
}
