package com.storyverse.api.admin;

import java.time.LocalDate;

/**
 * Web Vitals do site (Umami) no período. {@code vitals} vem {@code null} (com {@code error}) quando o
 * Umami não está configurado ou não respondeu; o resto do painel continua funcionando.
 */
public record AdminVitals(LocalDate from, LocalDate to, Vitals vitals, String error) {

	/**
	 * p75 de cada métrica: {@code lcp} e {@code inp} em milissegundos, {@code cls} sem unidade.
	 * {@code samples}: envios de performance no período. Métrica sem medida no período vem {@code null}.
	 */
	public record Vitals(Double lcp, Double inp, Double cls, long samples) {
	}
}
