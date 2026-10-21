package com.storyverse.api.common;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.storyverse.api.auth.AuthException;

/**
 * Usa o contador persistente quando configurado; se o banco estiver indisponível, cai no
 * contador local para não derrubar o site. O limite local continua protegendo uma instância.
 */
@Component
public class RateLimitGate {

	private static final Logger log = LoggerFactory.getLogger(RateLimitGate.class);

	private final RateLimits local;
	private final PersistentRateLimits persistent;
	private final boolean persistentEnabled;

	public RateLimitGate(
			RateLimits local,
			PersistentRateLimits persistent,
			@Value("${app.rate-limit.persistent:true}") boolean persistentEnabled) {
		this.local = local;
		this.persistent = persistent;
		this.persistentEnabled = persistentEnabled;
	}

	public void check(String key, int limit, Duration period) {
		if (persistentEnabled) {
			try {
				persistent.check(key, limit, period);
				return;
			} catch (AuthException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				// Inclui falha de conexão/transação do Postgres; o contador local assume.
				log.warn("Rate limit persistente indisponível; usando memória: {}", exception.getMessage());
			}
		}
		local.check(key, limit, period);
	}
}
