package com.storyverse.api.common;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.auth.AuthException;

/**
 * Contador no Postgres, compartilhado entre instâncias. Cada tentativa ocupa uma janela por
 * chave; a atualização é atômica e não depende da transação de negócio (REQUIRES_NEW).
 */
@Component
public class PersistentRateLimits {

	private static final Logger log = LoggerFactory.getLogger(PersistentRateLimits.class);

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;

	public PersistentRateLimits(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void check(String key, int limit, Duration period) {
		Integer count = transaction.execute(status -> jdbc.sql("""
				insert into public.api_rate_limits (bucket_key, window_start, count)
				values (:key, now(), 1)
				on conflict (bucket_key) do update
				   set count = case
				         when api_rate_limits.window_start <= now() - make_interval(secs => cast(:seconds as double precision))
				         then 1
				         else api_rate_limits.count + 1
				       end,
				       window_start = case
				         when api_rate_limits.window_start <= now() - make_interval(secs => cast(:seconds as double precision))
				         then now()
				         else api_rate_limits.window_start
				       end
				returning count
				""")
				.param("key", key)
				.param("seconds", period.toSeconds())
				.query(Integer.class)
				.single());
		if (count != null && count > limit) {
			throw new AuthException(429, "Muitas tentativas. Aguarde e tente novamente.");
		}
	}

	/** Limpa janelas antigas; a tabela não cresce indefinidamente. */
	@Scheduled(fixedRate = 3_600_000L)
	public void cleanup() {
		try {
			jdbc.sql("delete from public.api_rate_limits where window_start < now() - interval '1 day'").update();
		} catch (RuntimeException exception) {
			log.warn("Falha ao limpar rate limits antigos: {}", exception.getMessage());
		}
	}
}
