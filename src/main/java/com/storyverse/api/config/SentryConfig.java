package com.storyverse.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.SentryOptions.BeforeSendCallback;
import jakarta.annotation.PostConstruct;

/**
 * Inicialização manual do Sentry. O starter do Spring Boot ainda não reconhece o Boot 4 e
 * quebrava o contexto; aqui usamos apenas o núcleo da SDK.
 */
@Configuration
public class SentryConfig {

	private final BeforeSendCallback scrubPii;
	private final String dsn;
	private final String environment;
	private final String release;
	private final double tracesSampleRate;

	public SentryConfig(
			BeforeSendCallback scrubPii,
			@Value("${sentry.dsn:}") String dsn,
			@Value("${sentry.environment:}") String environment,
			@Value("${sentry.release:}") String release,
			@Value("${sentry.traces-sample-rate:0.0}") double tracesSampleRate) {
		this.scrubPii = scrubPii;
		this.dsn = dsn;
		this.environment = environment;
		this.release = release;
		this.tracesSampleRate = tracesSampleRate;
	}

	@PostConstruct
	void init() {
		if (dsn == null || dsn.isBlank()) {
			return;
		}
		Sentry.init((SentryOptions options) -> {
			options.setDsn(dsn);
			if (environment != null && !environment.isBlank()) {
				options.setEnvironment(environment);
			}
			if (release != null && !release.isBlank()) {
				options.setRelease(release);
			}
			options.setTracesSampleRate(Math.min(1, Math.max(0, tracesSampleRate)));
			options.setSendDefaultPii(false);
			options.setBeforeSend(scrubPii);
		});
	}
}
