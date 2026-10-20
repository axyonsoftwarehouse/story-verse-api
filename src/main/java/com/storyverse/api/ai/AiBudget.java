package com.storyverse.api.ai;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimits;

/**
 * Orçamento de IA no servidor: cota diária por conta, limite por minuto e um teto global
 * opcional (modo economia). O crédito é reservado antes da chamada paga; se o provedor falhar,
 * a mensagem ainda conta, como já acontecia antes.
 */
@Component
public class AiBudget {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private final JdbcClient jdbc;
	private final RateLimits limits;
	private final Semaphore concurrent = new Semaphore(4);
	private final int dailyLimit;
	private final int globalLimit;
	private final int perMinuteLimit;

	public AiBudget(
			JdbcClient jdbc,
			RateLimits limits,
			@Value("${app.ai.daily-message-limit:50}") int dailyLimit,
			@Value("${app.ai.daily-global-limit:0}") int globalLimit,
			@Value("${app.ai.per-minute-limit:6}") int perMinuteLimit) {
		this.jdbc = jdbc;
		this.limits = limits;
		this.dailyLimit = Math.max(1, dailyLimit);
		this.globalLimit = Math.max(0, globalLimit);
		this.perMinuteLimit = Math.max(1, perMinuteLimit);
	}

	@Transactional
	public AiQuota reserve(UUID user) {
		DbSession.setUser(jdbc, user);
		limits.check("ai:" + user, perMinuteLimit, Duration.ofMinutes(1));

		var used = jdbc.sql("""
				insert into public.api_daily_usage(user_id, day, feature, used)
				values (:uid, (now() at time zone 'America/Sao_Paulo')::date, 'ai', 1)
				on conflict (user_id, day, feature) do update
				   set used = api_daily_usage.used + 1
				 where api_daily_usage.used < :limit
				returning used
				""")
				.param("uid", user)
				.param("limit", dailyLimit)
				.query(Integer.class)
				.optional();
		if (used.isEmpty()) {
			throw new AuthException(429, "Limite diário de mensagens atingido.", "AI_QUOTA_EXHAUSTED");
		}

		if (globalLimit > 0) {
			var global = jdbc.sql("""
					insert into public.api_global_usage(day, used)
					values ((now() at time zone 'America/Sao_Paulo')::date, 1)
					on conflict (day) do update
					   set used = api_global_usage.used + 1,
					       updated_at = now()
					 where api_global_usage.used < :limit
					returning used
					""")
					.param("limit", globalLimit)
					.query(Integer.class)
					.optional();
			if (global.isEmpty()) {
				throw new AuthException(429,
						"A IA está em modo economia. Tente novamente mais tarde.",
						"AI_BUDGET_EXHAUSTED");
			}
		}

		return quota(used.get());
	}

	@Transactional(readOnly = true)
	public AiQuota quota(UUID user) {
		DbSession.setUser(jdbc, user);
		int used = jdbc.sql("""
				select coalesce((
				  select used from public.api_daily_usage
				   where user_id = :uid
				     and day = (now() at time zone 'America/Sao_Paulo')::date
				     and feature = 'ai'
				), 0)
				""").param("uid", user).query(Integer.class).single();
		return quota(used);
	}

	public void acquire() {
		if (!concurrent.tryAcquire()) {
			throw new AuthException(429, "Serviço ocupado. Tente em instantes.");
		}
	}

	public void release() {
		concurrent.release();
	}

	private AiQuota quota(int used) {
		LocalDate day = LocalDate.now(ZONE);
		int safeUsed = Math.max(0, used);
		return new AiQuota(
				dailyLimit,
				safeUsed,
				Math.max(0, dailyLimit - safeUsed),
				day.toString(),
				day.plusDays(1).atStartOfDay(ZONE).toInstant().toString());
	}
}
