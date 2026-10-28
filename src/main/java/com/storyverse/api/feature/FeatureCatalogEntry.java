package com.storyverse.api.feature;

/**
 * Uma feature conhecida e quantas contas a têm ligada por concessão. {@code everyone}: ligada
 * para todas as contas pela configuração; aí a concessão por conta não vale.
 */
public record FeatureCatalogEntry(String feature, long enabledCount, boolean everyone) {
}
