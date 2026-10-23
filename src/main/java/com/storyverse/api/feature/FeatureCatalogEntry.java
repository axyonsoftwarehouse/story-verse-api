package com.storyverse.api.feature;

/** Uma feature conhecida e quantas contas a têm ligada. */
public record FeatureCatalogEntry(String feature, long enabledCount) {
}
