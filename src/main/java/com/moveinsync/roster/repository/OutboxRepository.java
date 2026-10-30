package com.moveinsync.roster.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxRepository {

    /** Advisory lock key: only one relay (across all app instances) publishes at a time. */
    private static final long RELAY_LOCK_KEY = 7_243_001L;

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, UUID tenantId, UUID aggregateId, String type, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox_event (event_id, tenant_id, aggregate_id, event_type, payload)
                VALUES (:e, :t, :a, :type, CAST(:p AS jsonb))""")
                .param("e", eventId).param("t", tenantId).param("a", aggregateId)
                .param("type", type).param("p", payloadJson)
                .update();
    }

    /** Transaction-scoped; released automatically on commit/rollback (including a crash). */
    public boolean tryRelayLock() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)")
                .param("k", RELAY_LOCK_KEY).query(Boolean.class).single());
    }

    /** Oldest unpublished events first: global order = commit order of ids. */
    public List<OutboxRow> nextBatch(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, tenant_id, aggregate_id, event_type, payload::text AS payload, attempts, next_attempt_at
                FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT :l""")
                .param("l", limit)
                .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts"),
                        Sql.instant(rs, "next_attempt_at")))
                .list();
    }

    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id IN (:ids)").param("ids", ids).update();
    }

    public void markFailed(long id, String errorClass, Instant nextAttemptAt) {
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :err, next_attempt_at = :n WHERE id = :id")
                .param("err", errorClass).param("n", Sql.ts(nextAttemptAt)).param("id", id)
                .update();
    }

    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL").query(Long.class).single();
    }

    public Optional<Instant> oldestPendingCreatedAt() {
        List<Instant> r = jdbc.sql("SELECT min(created_at) AS m FROM outbox_event WHERE published_at IS NULL")
                .query((rs, n) -> Sql.instant(rs, "m")).list();
        return r.isEmpty() ? Optional.empty() : Optional.ofNullable(r.get(0));
    }
}
