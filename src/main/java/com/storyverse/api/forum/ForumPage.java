package com.storyverse.api.forum;

import java.util.List;
import java.util.Map;

public record ForumPage(List<Map<String, Object>> items, String nextCursor) {
}
