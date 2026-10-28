package com.storyverse.api.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.MailService;

/**
 * Pix não renova sozinho: avisa uma vez quando a sequência paga termina em até 3 dias. Quem paga
 * de novo antes não recebe aviso (o período novo vira o fim da sequência).
 */
@Component
public class PrepaidReminderJob {

	private static final Logger log = LoggerFactory.getLogger(PrepaidReminderJob.class);
	static final Duration AHEAD = Duration.ofDays(3);

	private final BillingRepository repository;
	private final MailService mail;

	public PrepaidReminderJob(BillingRepository repository, MailService mail) {
		this.repository = repository;
		this.mail = mail;
	}

	@Scheduled(cron = "0 10 * * * *", zone = "America/Sao_Paulo")
	@Transactional
	public void remind() {
		try {
			for (Map<String, Object> row : repository.prepaidEndingSoon(Instant.now().plus(AHEAD))) {
				Object email = row.get("email");
				if (email == null) continue;
				try {
					mail.sendPrepaidEnding(String.valueOf(email), BillingService.instant(row.get("ends_at")));
					repository.markPrepaidReminded((UUID) row.get("id"));
				} catch (RuntimeException exception) {
					// Fica sem marca e tenta de novo na próxima hora.
					log.warn("Aviso de fim do Pix não enviado: {}", exception.getMessage());
				}
			}
		} catch (RuntimeException exception) {
			log.warn("Aviso de fim do Pix falhou: {}", exception.getMessage());
		}
	}
}
