package com.story_magic.api.catalog.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.story_magic.api.catalog.application.BookCatalogRepository;
import com.story_magic.api.catalog.domain.Book;

@Repository
public class InMemoryBookRepository implements BookCatalogRepository {

	private final List<Book> books = List.of(
			book(74475, "A Escrava Isaura", "Bernardo Guimarães", "Romance", "pt", "pt",
					"Isaura é bela, culta e escravizada. Leôncio, seu senhor, a deseja a qualquer preço."),
			book(16425, "Amor de Perdição", "Camilo Castelo Branco", "Romance proibido", "pt", "pt",
					"Simão e Teresa se amam, mas suas famílias se odeiam."),
			book(42942, "O Primo Basílio", "Eça de Queirós", "Drama e paixão", "pt", "pt",
					"Luísa reencontra o primo Basílio em uma história de paixão, cartas perdidas e segredos."),
			book(67740, "Iracema", "José de Alencar", "Romance lendário", "pt", "pt",
					"Uma história de amor e sacrifício nas praias do Ceará."),
			book(345, "Drácula", "Bram Stoker", "Terror", "en", "terror",
					"Um jovem advogado viaja à Transilvânia para fechar negócio com um conde recluso.")
	);

	@Override
	public List<Book> findAll() {
		return books;
	}

	@Override
	public Optional<Book> findById(String id) {
		return books.stream()
				.filter(book -> book.id().equals(id))
				.findFirst();
	}

	private static Book book(
			int gutenbergId,
			String title,
			String author,
			String genre,
			String textLanguage,
			String shelf,
			String blurb) {
		return new Book(
				"gb-" + gutenbergId,
				gutenbergId,
				title,
				author,
				genre,
				blurb,
				"https://www.gutenberg.org/cache/epub/" + gutenbergId + "/pg" + gutenbergId + ".cover.medium.jpg",
				textLanguage,
				shelf);
	}
}
