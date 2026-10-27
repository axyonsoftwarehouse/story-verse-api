package com.storyverse.api.admin;

import java.util.List;

/**
 * Saúde do sistema no painel. {@code sentry} vem {@code null} (com {@code error}) quando o Sentry
 * não está configurado ou não respondeu; o resto do painel continua funcionando.
 */
public record AdminHealth(String period, Sentry sentry, String error, AdminOverview.Backup backup) {

	public record Sentry(List<Project> projects) {
	}

	/** {@code crashFreeSessions} é {@code null} em projeto sem sessões (ex.: a API). */
	public record Project(String slug, List<ErrorsPoint> errorsSeries, long errorsTotal,
			Double crashFreeSessions, List<SessionsPoint> sessionsSeries, List<Issue> topIssues) {
	}

	public record ErrorsPoint(String day, long accepted, long filtered) {
	}

	public record SessionsPoint(String day, long sessions) {
	}

	/** Só o que o painel mostra: nada de stack trace nem dados de usuário. */
	public record Issue(String id, String title, long count, long users, String lastSeen, String permalink) {
	}
}
