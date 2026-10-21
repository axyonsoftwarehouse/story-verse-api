package com.storyverse.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.sentry.Sentry;
import io.sentry.SentryLevel;

/**
 * Eventos de segurança sem PII: só o nome do evento e um nível. O Sentry remove usuário,
 * requisição, extras e breadcrumbs; os alertas podem ser criados no painel por tag/nível.
 */
@Component
public class SecurityEvents {

	private static final Logger log = LoggerFactory.getLogger(SecurityEvents.class);

	public void warn(String event) {
		log.warn("security event={}", event);
		capture(event, SentryLevel.WARNING);
	}

	public void info(String event) {
		log.info("security event={}", event);
	}

	private void capture(String event, SentryLevel level) {
		try {
			Sentry.captureMessage("security:" + event, level, options -> {
				options.setTag("security.event", event);
			});
		} catch (RuntimeException ignored) {
			// Sem Sentry configurado ou indisponível: o log local continua.
		}
	}
}
