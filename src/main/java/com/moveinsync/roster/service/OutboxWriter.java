package com.moveinsync.roster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.repository.OutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a change event to the outbox. Must run inside the transaction that made the change
 * (MANDATORY), so the change and its event commit or roll back together. Payloads hold IDs,
 * versions and changed field names only, never personal data.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repo;
    private final ObjectMapper json;
    private final RosterMetrics metrics;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repo, ObjectMapper json, RosterMetrics metrics, Clock clock) {
        this.repo = repo;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(TenantId tenant, UUID aggregateId, String type, Map<String, Object> body) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("type", type);
        payload.put("tenantId", tenant.value());
        payload.put("employeeId", aggregateId);
        payload.put("occurredAt", clock.instant().toString());
        payload.putAll(body);
        try {
            repo.insert(eventId, tenant.value(), aggregateId, type, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise change event", e);
        }
        metrics.changeEvent(type);
        return eventId;
    }
}
