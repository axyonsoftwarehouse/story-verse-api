package com.storyverse.api.feature;

import java.util.UUID;

public record FeatureGrantRequest(UUID userId, String feature, Boolean enabled) {
}
