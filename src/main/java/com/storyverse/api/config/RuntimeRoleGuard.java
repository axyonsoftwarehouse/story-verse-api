package com.storyverse.api.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Falha o boot quando o usuário do banco pode ignorar a RLS (owner, superuser ou bypassrls).
 *
 * <p>A autorização em Java é a primeira barreira; a RLS é a última. Se o runtime for dono das
 * tabelas (como acontece ao copiar o `.env.example` antigo ou rodar fora do Compose), a RLS deixa
 * de valer silenciosamente. Em Postgres de teste/H2 a checagem é ignorada; em produção o padrão é
 * exigir um papel dedicado.
 */
@Component
public class RuntimeRoleGuard implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RuntimeRoleGuard.class);

	private final DataSource dataSource;
	private final JdbcClient jdbc;
	private final boolean required;

	public RuntimeRoleGuard(DataSource dataSource, JdbcClient jdbc,
			@Value("${app.security.require-runtime-role:true}") boolean required) {
		this.dataSource = dataSource;
		this.jdbc = jdbc;
		this.required = required;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!required || !isPostgres()) {
			return;
		}
		Boolean privileged = jdbc.sql("""
				select rolsuper or rolbypassrls
				  from pg_roles
				 where rolname = current_user
				""").query(Boolean.class).single();
		if (Boolean.TRUE.equals(privileged)) {
			throw new IllegalStateException(
					"O usuário do banco pode ignorar RLS (superuser/bypassrls). Use o papel storyverse_api.");
		}
		Boolean owner = jdbc.sql("""
				select exists (
				  select 1 from pg_tables
				   where schemaname in ('auth', 'public')
				     and tableowner = current_user
				)
				""").query(Boolean.class).single();
		if (Boolean.TRUE.equals(owner)) {
			throw new IllegalStateException(
					"O usuário do banco é dono de tabelas da aplicação. Use o papel storyverse_api.");
		}
		log.info("Papel de runtime validado: RLS aplicável.");
	}

	private boolean isPostgres() {
		try (Connection connection = dataSource.getConnection()) {
			return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
		} catch (SQLException exception) {
			throw new IllegalStateException("Não foi possível verificar o banco de dados.", exception);
		}
	}
}
