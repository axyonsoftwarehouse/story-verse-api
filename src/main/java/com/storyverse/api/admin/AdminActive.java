package com.storyverse.api.admin;

import java.time.LocalDate;
import java.util.List;

/**
 * Pessoas com request autenticado: no último dia do período (DAU), nos 7 dias (WAU) e nos 30 dias
 * (MAU) que terminam nele, e a série diária do período.
 */
public record AdminActive(long dau, long wau, long mau, List<Point> series) {

	public record Point(LocalDate day, long active) {
	}
}
