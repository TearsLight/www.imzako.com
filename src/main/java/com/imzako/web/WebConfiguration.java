package com.imzako.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    public WebConfiguration(@Value("${ALLOWED_ORIGINS:http://localhost:5173,http://localhost:8080}") String configuredOrigins) {
        origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> origin.startsWith("http://") || origin.startsWith("https://"))
                .distinct()
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) return;
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept")
                .exposedHeaders("X-Captcha-Id", "X-Captcha-Threshold")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
