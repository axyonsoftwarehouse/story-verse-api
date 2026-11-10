package com.storyverse.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * S10 da revisão de 09/11/2026: sessões nunca eram podadas (a passada de QA de 07/11 viu dezenas
 * numa conta só). Uma vez por dia, apaga as vencidas e as revogadas há mais de uma semana.
 */
@Component
public class SessionCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(SessionCleanupJob.class);
	static final int KEEP_REVOKED_DAYS = 7;

	private final SessionRepository sessions;

	public SessionCleanupJob(SessionRepository sessions) {
		this.sessions = sessions;
	}

	@Scheduled(cron = "0 20 4 * * *", zone = "America/Sao_Paulo")
	@Transactional
	public void prune() {
		try {
			int removed = sessions.pruneDead(KEEP_REVOKED_DAYS);
			if (removed > 0) log.info("Sessões podadas: {}", removed);
		} catch (RuntimeException exception) {
			log.warn("Falha ao podar sessões: {}", exception.getMessage());
		}
	}
}
