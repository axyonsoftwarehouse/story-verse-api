package com.storyverse.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * O frontend fica em outro subdomínio (ex.: storyverse.…sslip.io) e chama a API em
 * api.storyverse.…sslip.io. O access token continua em Bearer; o refresh token vai em cookie
 * HttpOnly, então a origem exata precisa de credenciais (nunca use curinga).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final String[] allowedOrigins;

	public CorsConfig(
			@Value("${app.cors.allowed-origins:https://storyverse.2.29.42.104.sslip.io}") String[] allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins)
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.allowCredentials(true)
				.maxAge(3600);
	}
}
