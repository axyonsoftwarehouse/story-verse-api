package com.storyverse.api.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configura e roda o Flyway de forma explícita (não depende da auto-configuração do Spring Boot,
 * que no Boot 4 vive em um módulo próprio). O schema base (database/schema.sql) precisa existir
 * antes; aqui a única migração é auth.sessions.
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

	@Bean(initMethod = "migrate")
	Flyway flyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.load();
	}
}
