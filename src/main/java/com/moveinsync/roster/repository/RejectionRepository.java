package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.model.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class RejectionRepository {

    public record Rejection(int recordIndex, String externalId, String field, String errorCode) {
    }

    private final JdbcClient jdbc;

    public RejectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: replaying the same batch does not add rows. */
    public void insert(TenantId tenant, String source, UUID refId, int index, String externalId, String field, String code) {
        jdbc.sql("""
                INSERT INTO ingest_rejection (tenant_id, source, ref_id, record_index, external_id, field, error_code)
                VALUES (:t, :s, :r, :i, :e, :f, :c) ON CONFLICT DO NOTHING""")
                .param("t", tenant.value()).param("s", source).param("r", refId).param("i", index)
                .param("e", externalId).param("f", field).param("c", code)
                .update();
    }

    public List<Rejection> list(TenantId tenant, UUID refId, int limit) {
        return jdbc.sql("""
                SELECT record_index, external_id, field, error_code FROM ingest_rejection
                WHERE tenant_id = :t AND ref_id = :r ORDER BY record_index, field LIMIT :l""")
                .param("t", tenant.value()).param("r", refId).param("l", limit)
                .query((rs, n) -> new Rejection(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }
}
