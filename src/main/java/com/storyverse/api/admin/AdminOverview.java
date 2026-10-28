package com.storyverse.api.admin;

import java.time.Instant;
import java.util.Map;

/** Visão geral do painel. Cada bloco vem {@code null} quando o papel do ator não pode vê-lo. */
public record AdminOverview(Moderation moderation, Accounts accounts, Map<String, Integer> features,
		Backup backup) {

	public record Moderation(int pendingSubmissions, int openReports) {
	}

	public record Accounts(int total, int newLast7Days) {
	}

	/** Último backup (qualquer status); {@code stale} considera o último com sucesso. */
	public record Backup(String status, Instant finishedAt, Long sizeBytes, boolean stale) {
	}
}
