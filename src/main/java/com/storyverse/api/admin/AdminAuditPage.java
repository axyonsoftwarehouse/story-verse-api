package com.storyverse.api.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Uma página da auditoria; {@code nextBefore} é nulo na última. */
public record AdminAuditPage(List<Entry> items, Long nextBefore) {

	/** {@code actorName} é nulo quando {@code actorId} é nulo (script na VPS). */
	public record Entry(long id, UUID actorId, String actorName, String action, String targetType,
			String targetId, Map<String, Object> metadata, Instant createdAt) {
	}
}
