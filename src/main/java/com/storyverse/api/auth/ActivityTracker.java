package com.storyverse.api.auth;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.admin.AdminRange;
import com.storyverse.api.common.DbSession;

import jakarta.annotation.PreDestroy;

/**
 * Marca o dia (São Paulo) em que a pessoa fez um request autenticado, para o DAU/WAU/MAU do painel.
 * Só o primeiro request do dia vai ao banco: os demais param no conjunto em memória. A gravação
 * roda numa fila de uma thread só, fora do request, e usa no máximo uma conexão do pool.
 */
@Component
public class ActivityTracker {

	private static final Logger log = LoggerFactory.getLogger(ActivityTracker.class);

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(
			Thread.ofVirtual().name("activity-days").factory());
	private final Set<UUID> seenToday = ConcurrentHashMap.newKeySet();
	private volatile LocalDate today = LocalDate.now(AdminRange.SAO_PAULO);

	public ActivityTracker(JdbcClient jdbc, PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactions);
	}

	public void touch(UUID userId) {
		LocalDate day = LocalDate.now(AdminRange.SAO_PAULO);
		if (!day.equals(today)) {
			// Virou o dia: esquece o anterior. Uma gravação repetida na virada não faz mal (on conflict).
			today = day;
			seenToday.clear();
		}
		if (!seenToday.add(userId)) {
			return;
		}
		try {
			writer.execute(() -> write(userId, day));
		} catch (RejectedExecutionException error) {
			seenToday.remove(userId);
		}
	}

	private void write(UUID userId, LocalDate day) {
		try {
			transaction.executeWithoutResult(status -> {
				DbSession.setUser(jdbc, userId);
				jdbc.sql("""
						insert into public.activity_days (user_id, day)
						values (:uid, :day)
						on conflict (user_id, day) do nothing
						""")
						.param("uid", userId)
						.param("day", Date.valueOf(day))
						.update();
			});
		} catch (RuntimeException error) {
			// Métrica de painel: uma falha aqui nunca afeta o request da pessoa.
			log.warn("activity_days não gravado: {}", error.getClass().getSimpleName());
		}
	}

	@PreDestroy
	void shutdown() {
		writer.shutdown();
	}
}
