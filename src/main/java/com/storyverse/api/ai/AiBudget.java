package com.storyverse.api.ai;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.common.RateLimitGate;

/**
 * Orçamento de IA no servidor: cota diária por conta, limite por minuto, teto global opcional
 * e créditos extras comprados com diamantes. A cota gratuita é tentada antes dos créditos.
 */
@Component
public class AiBudget {

	private final JdbcClient jdbc;
	private final RateLimitGate limits;
	private final Semaphore concurrent = new Semaphore(4);
	private final int dailyLimit;
	private final int globalLimit;
	private final int perMinuteLimit;

	public AiBudget(
			JdbcClient jdbc,
			RateLimitGate limits,
			@Value("${app.ai.daily-message-limit:50}") int dailyLimit,
			@Value("${app.ai.daily-global-limit:0}") int globalLimit,
			@Value("${app.ai.per-minute-limit:6}") int perMinuteLimit) {
		this.jdbc = jdbc;
		this.limits = limits;
		this.dailyLimit = Math.max(1, dailyLimit);
		this.globalLimit = Math.max(0, globalLimit);
		this.perMinuteLimit = Math.max(1, perMinuteLimit);
	}

	public record AiReservation(AiQuota quota, String source) {
	}

	private record CreditUse(boolean consumed, int creditsBalance, boolean duplicate) {
	}

	@Transactional
	public AiReservation reserve(UUID user, String requestId) {
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

		if (used.isPresent()) {
			reserveGlobal();
			return new AiReservation(quota(), "free");
		}

		// A cota gratuita acabou. O teto global vem antes para não consumir crédito em vão.
		reserveGlobal();
		String ref = requestId == null || requestId.isBlank()
				? UUID.randomUUID().toString()
				: requestId;
		CreditUse credit = jdbc.sql("select * from public.consume_ai_credit(:ref)")
				.param("ref", ref)
				.query((rs, rowNum) -> new CreditUse(
						rs.getBoolean("consumed"),
						rs.getInt("credits_balance"),
						rs.getBoolean("duplicate")))
				.single();
		// Um requestId repetido já foi cobrado (ou já falhou depois da cobrança). Nunca chame o
		// provedor de novo: sem esta checagem, o mesmo ref gerava respostas ilimitadas de graça.
		if (credit.duplicate()) {
			throw new AuthException(409,
					"Este pedido de IA já foi processado. Envie uma nova mensagem.",
					"AI_REQUEST_REPLAYED");
		}
		if (!credit.consumed()) {
			throw new AuthException(429,
					"Limite diário de mensagens atingido. Compre mensagens extras para continuar.",
					"AI_QUOTA_EXHAUSTED");
		}
		return new AiReservation(quota(), "credit");
	}

	@Transactional(readOnly = true)
	public AiQuota quota(UUID user) {
		DbSession.setUser(jdbc, user);
		return quota();
	}

	public void acquire() {
		if (!concurrent.tryAcquire()) {
			throw new AuthException(429, "Serviço ocupado. Tente em instantes.");
		}
	}

	public void release() {
		concurrent.release();
	}

	private AiQuota quota() {
		return jdbc.sql("select * from public.my_ai_quota(:limit)")
				.param("limit", dailyLimit)
				.query((rs, rowNum) -> new AiQuota(
						rs.getInt("daily_limit"),
						rs.getInt("used"),
						rs.getInt("free_remaining"),
						rs.getInt("credits"),
						rs.getDate("day").toString(),
						rs.getTimestamp("resets_at").toInstant().toString()))
				.single();
	}

	private void reserveGlobal() {
		if (globalLimit <= 0) {
			return;
		}
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
}
