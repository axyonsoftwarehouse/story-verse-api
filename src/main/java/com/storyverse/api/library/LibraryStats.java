package com.storyverse.api.library;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Números do ano para o perfil e a retrospectiva. `finished`, `rated`, `averageRating` e os mapas
 * contam livros concluídos no ano; `reading` e `wantToRead` são o estado atual da estante.
 */
public record LibraryStats(
		int year,
		int finished,
		int abandoned,
		int reading,
		int wantToRead,
		int rated,
		BigDecimal averageRating,
		Map<String, Integer> finishedByGenre,
		Map<Integer, Integer> finishedByMonth) {
}
