package com.storyverse.api.billing;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.storyverse.api.admin.AdminRange;

/**
 * Foto diária do MRR para a série do financeiro. Roda de hora em hora e reescreve a linha do dia,
 * então a última execução antes da meia-noite (São Paulo) é a que fica; uma hora fora do ar não
 * perde o dia. Com mais de uma instância o upsert é idempotente.
 */
@Component
public class BillingSnapshotJob {

	private static final Logger log = LoggerFactory.getLogger(BillingSnapshotJob.class);

	private final BillingRepository repository;

	public BillingSnapshotJob(BillingRepository repository) {
		this.repository = repository;
	}

	@Scheduled(cron = "0 55 * * * *", zone = "America/Sao_Paulo")
	public void snapshot() {
		try {
			repository.snapshot(LocalDate.now(AdminRange.SAO_PAULO));
		} catch (RuntimeException exception) {
			log.warn("Snapshot do financeiro falhou: {}", exception.getMessage());
		}
	}
}
