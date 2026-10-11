package com.story_magic.api.integrations.gutenberg;

public interface BookContentProvider {

	String fetchPlainText(int gutenbergId);
}
