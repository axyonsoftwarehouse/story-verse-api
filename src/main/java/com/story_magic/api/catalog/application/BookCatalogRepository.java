package com.story_magic.api.catalog.application;

import java.util.List;
import java.util.Optional;

import com.story_magic.api.catalog.domain.Book;

public interface BookCatalogRepository {

	List<Book> findAll();

	Optional<Book> findById(String id);
}
