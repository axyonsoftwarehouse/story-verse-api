package com.story_magic.api.integrations.openlibrary;

import java.util.List;

public interface OpenLibrarySearchProvider {

	List<OpenLibraryBookDocument> search(String query);
}
