package com.storyverse.api.feature;

import java.time.Instant;
import java.util.UUID;

/** Conta com uma feature ligada. O e-mail vai sempre mascarado. */
public record FeatureUserRow(UUID userId, String name, String emailMasked, String grantedByName, Instant updatedAt) {
}
