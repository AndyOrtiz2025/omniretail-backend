package com.omniretail.backend.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Antepone {@code app.api.base-path} (/api/v1) a todos los controllers del proyecto, para que cada
 * modulo declare solo su recurso: {@code @RequestMapping("/catalog/products")} queda en
 * {@code /api/v1/catalog/products}. No afecta a Actuator ni a Swagger.
 */
@Configuration
public class ApiPathConfig implements WebMvcConfigurer {

    private final String basePath;

    public ApiPathConfig(@Value("${app.api.base-path}") String basePath) {
        this.basePath = basePath;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(basePath, HandlerTypePredicate.forBasePackage("com.omniretail.backend"));
    }
}
