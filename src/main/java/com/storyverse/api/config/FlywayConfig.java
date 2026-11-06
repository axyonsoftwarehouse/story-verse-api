package com.storyverse.api.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Configura e roda o Flyway de forma explícita (não depende da auto-configuração do Spring Boot,
 * que no Boot 4 vive em um módulo próprio). O schema base (database/schema.sql) precisa existir
 * antes; aqui a única migração é auth.sessions.
 *
 * <p>{@code outOfOrder}: duas pessoas podem criar migrações em paralelo (main e develop). Quando a de
 * número menor chega depois (ex.: V48 do develop depois da V49 já aplicada), ela é aplicada em vez
 * de travar a subida da API. Por isso toda migração precisa poder rodar fora de ordem
 * (create or replace, if not exists...).
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

	private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

	/**
	 * Migrações que mudaram de número: o registro antigo sai do histórico antes da validação (senão
	 * o Flyway acusa "aplicada mas não encontrada" ou checksum diferente). A roleta saiu como V48 no
	 * main no mesmo dia em que o develop criou a V48 do teto de leitura; virou V49 (create or replace:
	 * rodar de novo não muda nada).
	 */
	static final String[][] RENUMBERED = { { "48", "V48__roleta_premios_maiores.sql" } };

	@Bean(initMethod = "migrate")
	Flyway flyway(DataSource dataSource,
			@Value("${spring.flyway.url:}") String url,
			@Value("${spring.flyway.user:}") String user,
			@Value("${spring.flyway.password:}") String password) {
		FluentConfiguration config = Flyway.configure();
		if (url.isBlank()) config.dataSource(dataSource);
		else config.dataSource(url, user, password);
		forgetRenumbered(config.getDataSource());
		return config
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.outOfOrder(true)
				.load();
	}

	/** Tira do histórico os registros de migrações que trocaram de número (se existirem). */
	static void forgetRenumbered(DataSource dataSource) {
		try (Connection connection = dataSource.getConnection()) {
			try (PreparedStatement exists = connection.prepareStatement("select to_regclass('flyway_schema_history') is not null");
					ResultSet rs = exists.executeQuery()) {
				if (!rs.next() || !rs.getBoolean(1)) return; // banco novo: nada a limpar
			}
			try (PreparedStatement delete = connection.prepareStatement(
					"delete from flyway_schema_history where version = ? and script = ?")) {
				for (String[] entry : RENUMBERED) {
					delete.setString(1, entry[0]);
					delete.setString(2, entry[1]);
					if (delete.executeUpdate() > 0) log.info("Flyway: registro antigo de {} removido do histórico", entry[1]);
				}
			}
		} catch (SQLException exception) {
			// Sem permissão ou sem banco agora: a validação do Flyway decide (e mostra o erro real).
			log.warn("Flyway: não foi possível limpar migrações renumeradas: {}", exception.getMessage());
		}
	}
}
