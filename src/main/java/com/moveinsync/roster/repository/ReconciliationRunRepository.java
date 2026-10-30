package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.model.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReconciliationRunRepository {

    private static final RowMapper<ReconciliationRun> MAPPER = (rs, n) -> new ReconciliationRun(
            rs.getObject("run_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("status"),
            rs.getString("file_path"), Sql.instant(rs, "as_of"), (Integer) rs.getObject("expected_rows"),
            rs.getInt("rows_read"), rs.getInt("rows_rejected"), rs.getBoolean("truncated"),
            rs.getInt("active_before"), rs.getInt("to_create"), rs.getInt("to_update"), rs.getInt("to_deactivate"),
            rs.getObject("missing_ratio") == null ? null : rs.getDouble("missing_ratio"),
            rs.getObject("threshold") == null ? null : rs.getDouble("threshold"),
            rs.getString("brake_reasons"), rs.getInt("applied"), rs.getInt("stale"), rs.getInt("deactivated"),
            (Long) rs.getObject("diff_millis"), rs.getString("error_code"), rs.getString("decided_by"),
            Sql.instant(rs, "decided_at"), Sql.instant(rs, "created_at"));

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ReconciliationRunRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Fails with DuplicateKeyException if the tenant already has a run in flight. */
    public void insert(UUID runId, TenantId tenant, String filePath) {
        jdbc.sql("INSERT INTO reconciliation_run (run_id, tenant_id, status, file_path) VALUES (:r, :t, 'RECEIVED', :f)")
                .param("r", runId).param("t", tenant.value()).param("f", filePath).update();
    }

    public Optional<ReconciliationRun> find(TenantId tenant, UUID runId) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE tenant_id = :t AND run_id = :r")
                .param("t", tenant.value()).param("r", runId).query(MAPPER).optional();
    }

    public Optional<ReconciliationRun> findInternal(UUID runId) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE run_id = :r").param("r", runId).query(MAPPER).optional();
    }

    public List<ReconciliationRun> list(TenantId tenant, int limit) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE tenant_id = :t ORDER BY created_at DESC LIMIT :l")
                .param("t", tenant.value()).param("l", limit).query(MAPPER).list();
    }

    public List<ReconciliationRun> findByStatus(List<String> statuses) {
        return jdbc.sql("SELECT * FROM reconciliation_run WHERE status IN (:s)").param("s", statuses).query(MAPPER).list();
    }

    public long countByStatus(String status) {
        return jdbc.sql("SELECT count(*) FROM reconciliation_run WHERE status = :s").param("s", status)
                .query(Long.class).single();
    }

    /** Compare-and-set on status; false if someone else moved the run first. */
    public boolean transition(UUID runId, String from, String to) {
        return jdbc.sql("UPDATE reconciliation_run SET status = :to, updated_at = now() WHERE run_id = :r AND status = :from")
                .param("to", to).param("r", runId).param("from", from).update() == 1;
    }

    public void fail(UUID runId, String errorCode) {
        jdbc.sql("UPDATE reconciliation_run SET status = 'FAILED', error_code = :c, updated_at = now() WHERE run_id = :r")
                .param("c", errorCode).param("r", runId).update();
    }

    public void decide(UUID runId, String operator) {
        jdbc.sql("UPDATE reconciliation_run SET decided_by = :o, decided_at = now() WHERE run_id = :r")
                .param("o", operator).param("r", runId).update();
    }

    public void saveDiff(UUID runId, Instant asOf, Integer expectedRows, int rowsRead, int rowsRejected,
                         boolean truncated, int activeBefore, int toCreate, int toUpdate, int toDeactivate,
                         double missingRatio, double threshold, String brakeReasons, long diffMillis) {
        jdbc.sql("""
                UPDATE reconciliation_run SET as_of = :asOf, expected_rows = :exp, rows_read = :rr, rows_rejected = :rj,
                    truncated = :tr, active_before = :ab, to_create = :tc, to_update = :tu, to_deactivate = :td,
                    missing_ratio = :mr, threshold = :th, brake_reasons = :br, diff_millis = :dm, updated_at = now()
                WHERE run_id = :r""")
                .param("asOf", Sql.ts(asOf)).param("exp", expectedRows).param("rr", rowsRead).param("rj", rowsRejected)
                .param("tr", truncated).param("ab", activeBefore).param("tc", toCreate).param("tu", toUpdate)
                .param("td", toDeactivate).param("mr", missingRatio).param("th", threshold).param("br", brakeReasons)
                .param("dm", diffMillis).param("r", runId)
                .update();
    }

    public void saveApplied(UUID runId, int applied, int stale, int deactivated) {
        jdbc.sql("UPDATE reconciliation_run SET applied = :a, stale = :s, deactivated = :d, updated_at = now() WHERE run_id = :r")
                .param("a", applied).param("s", stale).param("d", deactivated).param("r", runId).update();
    }

    public void clearCandidates(UUID runId) {
        jdbc.sql("DELETE FROM reconciliation_candidate WHERE run_id = :r").param("r", runId).update();
    }

    public void insertCandidates(UUID runId, List<UUID> employeeIds, List<Long> versions) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO reconciliation_candidate (run_id, employee_id, hr_version_at_diff) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setObject(1, runId);
                        ps.setObject(2, employeeIds.get(i));
                        ps.setLong(3, versions.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return employeeIds.size();
                    }
                });
    }

    /** Version recorded at diff time if the employee was approved for deactivation in this run. */
    public Optional<Long> candidateVersion(UUID runId, UUID employeeId) {
        return jdbc.sql("SELECT hr_version_at_diff FROM reconciliation_candidate WHERE run_id = :r AND employee_id = :e")
                .param("r", runId).param("e", employeeId).query(Long.class).optional();
    }

    public List<String> sampleCandidateExternalIds(UUID runId, int limit) {
        return jdbc.sql("""
                SELECT e.external_id FROM reconciliation_candidate c JOIN employee e ON e.employee_id = c.employee_id
                WHERE c.run_id = :r ORDER BY e.external_id LIMIT :l""")
                .param("r", runId).param("l", limit).query(String.class).list();
    }
}
