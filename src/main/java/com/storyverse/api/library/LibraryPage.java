package com.storyverse.api.library;

import java.util.List;

/** Uma página da estante; `nextCursor` null quando acabou. */
public record LibraryPage(List<LibraryEntry> items, String nextCursor) {
}
