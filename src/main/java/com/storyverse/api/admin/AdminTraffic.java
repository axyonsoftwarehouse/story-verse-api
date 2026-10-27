package com.storyverse.api.admin;

import java.time.LocalDate;
import java.util.List;

/**
 * Tráfego do site (Umami) no período. {@code traffic} vem {@code null} (com {@code error}) quando o
 * Umami não está configurado ou não respondeu; o resto do painel continua funcionando.
 */
public record AdminTraffic(LocalDate from, LocalDate to, Traffic traffic, String error) {

	/**
	 * {@code bounceRate}: visitas de uma página só ÷ visitas. {@code previous}: o período anterior de
	 * mesmo tamanho, para a comparação do painel.
	 */
	public record Traffic(long visitors, long pageviews, long visits, double bounceRate, long avgVisitSeconds,
			long activeNow, Totals previous, List<Point> series, List<Entry> topPages, List<Entry> referrers,
			List<Entry> devices, List<Entry> countries) {
	}

	public record Totals(long visitors, long pageviews, long visits) {
	}

	public record Point(String day, long visitors, long pageviews) {
	}

	/** {@code value} vazio em referrers é acesso direto (sem origem). */
	public record Entry(String value, long count) {
	}
}
