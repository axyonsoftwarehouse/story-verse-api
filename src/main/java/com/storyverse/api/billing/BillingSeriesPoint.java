package com.storyverse.api.billing;

import java.time.LocalDate;

/**
 * Um bucket (dia ou semana, segunda-feira) da série do financeiro.
 *
 * <p>{@code mrrCents}/{@code active}: fotografia do último dia do bucket. {@code estimated} indica
 * que aquele dia não tinha snapshot e o valor foi reconstruído pelas assinaturas (ver V25).
 */
public record BillingSeriesPoint(LocalDate bucket, long mrrCents, long active, long newCount,
		long canceledCount, long revenueCents, boolean estimated) {
}
