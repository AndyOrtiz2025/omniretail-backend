package com.omniretail.backend.commercial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cuerpos de prueba del formulario comercial. */
final class ContactRequestPayloads {

    static final String PATH = "/api/v1/public/contact-requests";

    private ContactRequestPayloads() {
    }

    static String uniqueEmail() {
        return "interesado-" + UUID.randomUUID() + "@example.test";
    }

    /** Solicitud valida; cada prueba cambia solo lo que necesita. */
    static Map<String, Object> valid(String email, String businessName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Ana Lucía Pérez");
        body.put("businessName", businessName);
        body.put("email", email);
        body.put("phone", "+502 5555-1234");
        body.put("industry", "retail");
        body.put("modules", List.of("inventory", "pos"));
        body.put("message", "Nos interesa conocer una demostración.");
        body.put("consentAccepted", true);
        body.put("consentVersion", "2026-10");
        body.put("website", "");
        return body;
    }

    static Map<String, Object> valid() {
        return valid(uniqueEmail(), "Negocio " + UUID.randomUUID());
    }
}
