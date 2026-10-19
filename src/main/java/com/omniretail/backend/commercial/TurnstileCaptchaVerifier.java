package com.omniretail.backend.commercial;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cloudflare Turnstile (compatible con cualquier verificador de la misma forma). Sin dependencias nuevas: HttpClient
 * del JDK. Sin token, con timeout o con una respuesta ilegible la solicitud se rechaza; el secreto nunca se registra.
 */
@Slf4j
final class TurnstileCaptchaVerifier implements CaptchaVerifier {

    private final ContactRequestProperties.Captcha settings;
    private final JsonMapper jsonMapper;
    private final HttpClient client;

    TurnstileCaptchaVerifier(ContactRequestProperties.Captcha settings, JsonMapper jsonMapper) {
        this.settings = settings;
        this.jsonMapper = jsonMapper;
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    }

    @Override
    public boolean verify(String token, String clientIp) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            StringBuilder form = new StringBuilder("secret=").append(encode(settings.secret()))
                    .append("&response=").append(encode(token));
            if (clientIp != null && !clientIp.isBlank()) {
                form.append("&remoteip=").append(encode(clientIp));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.verifyUrl()))
                    .timeout(settings.timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && jsonMapper.readTree(response.body()).path("success").asBoolean(false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            log.warn("Verificacion anti-robots no disponible: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
