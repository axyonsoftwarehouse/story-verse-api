package com.storyverse.api.feature;

import java.util.List;

public record FeatureUsersPage(List<FeatureUserRow> items, String nextCursor) {
}
