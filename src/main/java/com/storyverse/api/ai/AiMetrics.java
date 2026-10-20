package com.storyverse.api.ai;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Métricas por chamada de IA. Guarda apenas metadados (provedor, modelo, latencia, tokens
 * estimados e código de erro), nunca o conteúdo da conversa. Uma falha aqui não pode derrubar
 * a resposta para o usuario.
 */
@Component
public class AiMetrics {

	private static final Logger log = LoggerFactory.getLogger(AiMetrics.class);

	private final JdbcClient jdbc;

	public AiMetrics(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(UUID userId, String kind, String provider, String model, String status,
			long latencyMs, int tokensIn, int tokensOut, boolean cacheHit, String errorCode) {
		try {
			jdbc.sql("""
					insert into public.ai_usage_events
					  (user_id, kind, provider, model, status, latency_ms, tokens_in, tokens_out,
					   cache_hit, error_code)
					values
					  (:userId, :kind, :provider, :model, :status, :latency, :tokensIn, :tokensOut,
					   :cacheHit, :errorCode)
					""")
					.param("userId", userId)
					.param("kind", kind)
					.param("provider", provider)
					.param("model", model)
					.param("status", status)
					.param("latency", Math.max(0, latencyMs))
					.param("tokensIn", Math.max(0, tokensIn))
					.param("tokensOut", Math.max(0, tokensOut))
					.param("cacheHit", cacheHit)
					.param("errorCode", errorCode)
					.update();
		} catch (RuntimeException exception) {
			log.warn("Falha ao registrar métrica de IA: {}", exception.getMessage());
		}
	}
}
