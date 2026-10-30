package com.moveinsync.roster.repository;

import java.time.Instant;
import java.util.UUID;

public record OutboxRow(long id, UUID eventId, UUID tenantId, UUID aggregateId, String eventType,
                        String payload, int attempts, Instant nextAttemptAt) {
}
