package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;
import com.moveinsync.roster.domain.model.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is scoped by tenant. Callers can't read across tenants by construction. */
@Repository
public class EmployeeRepository {

    private static final String COLS = """
            employee_id, tenant_id, external_id, name, email, phone, manager_external_id, office_code,
            shift_code, status, deactivated_by, hr_version, hr_digest, hr_source_ts, bucket,
            pickup_point_id, special_assistance, admin_version, pickup_needs_review""";

    static final RowMapper<EmployeeState> MAPPER = (rs, n) -> new EmployeeState(
            rs.getObject("employee_id", UUID.class),
            new TenantId(rs.getObject("tenant_id", UUID.class)),
            rs.getString("external_id"),
            new HrProfile(rs.getString("name"), rs.getString("email"), rs.getString("phone"),
                    rs.getString("manager_external_id"), rs.getString("office_code"), rs.getString("shift_code")),
            EmployeeStatus.valueOf(rs.getString("status")),
            rs.getString("deactivated_by") == null ? null : DeactivatedBy.valueOf(rs.getString("deactivated_by")),
            rs.getLong("hr_version"),
            rs.getLong("hr_digest"),
            Sql.instant(rs, "hr_source_ts"),
            rs.getInt("bucket"),
            rs.getString("pickup_point_id"),
            rs.getString("special_assistance"),
            rs.getLong("admin_version"),
            rs.getBoolean("pickup_needs_review"));

    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Row lock: serialises all writers of one employee for the rest of the transaction. */
    public Optional<EmployeeState> lockByExternalId(TenantId tenant, String externalId) {
        return jdbc.sql("SELECT " + COLS + " FROM employee WHERE tenant_id = :t AND external_id = :e FOR UPDATE")
                .param("t", tenant.value()).param("e", externalId)
                .query(MAPPER).optional();
    }

    public Optional<EmployeeState> lockById(TenantId tenant, UUID employeeId) {
        return jdbc.sql("SELECT " + COLS + " FROM employee WHERE tenant_id = :t AND employee_id = :id FOR UPDATE")
                .param("t", tenant.value()).param("id", employeeId)
                .query(MAPPER).optional();
    }

    /** Shared lock: blocks while a deactivation of this employee is in flight. */
    public Optional<EmployeeState> lockForShareByExternalId(TenantId tenant, String externalId) {
        return jdbc.sql("SELECT " + COLS + " FROM employee WHERE tenant_id = :t AND external_id = :e FOR SHARE")
                .param("t", tenant.value()).param("e", externalId)
                .query(MAPPER).optional();
    }

    public Optional<EmployeeState> findByExternalId(TenantId tenant, String externalId) {
        return jdbc.sql("SELECT " + COLS + " FROM employee WHERE tenant_id = :t AND external_id = :e")
                .param("t", tenant.value()).param("e", externalId)
                .query(MAPPER).optional();
    }

    public List<EmployeeState> page(TenantId tenant, int limit, int offset) {
        return jdbc.sql("SELECT " + COLS + " FROM employee WHERE tenant_id = :t ORDER BY external_id LIMIT :l OFFSET :o")
                .param("t", tenant.value()).param("l", limit).param("o", offset)
                .query(MAPPER).list();
    }

    public int countActive(TenantId tenant) {
        return jdbc.sql("SELECT count(*) FROM employee WHERE tenant_id = :t AND status = 'ACTIVE'")
                .param("t", tenant.value()).query(Integer.class).single();
    }

    /** @return false if another transaction inserted the same employee first. */
    public boolean insertIfAbsent(EmployeeState e) {
        int n = jdbc.sql("""
                INSERT INTO employee (employee_id, tenant_id, external_id, name, email, phone, manager_external_id,
                    office_code, shift_code, status, deactivated_by, hr_version, hr_digest, hr_source_ts, bucket,
                    pickup_point_id, special_assistance, admin_version, pickup_needs_review)
                VALUES (:id, :t, :ext, :name, :email, :phone, :mgr, :office, :shift, :status, :deact, :hv, :hd,
                    :hts, :bucket, :pickup, :assist, :av, :review)
                ON CONFLICT (tenant_id, external_id) DO NOTHING""")
                .params(params(e))
                .update();
        return n == 1;
    }

    public void update(EmployeeState e) {
        jdbc.sql("""
                UPDATE employee SET name = :name, email = :email, phone = :phone, manager_external_id = :mgr,
                    office_code = :office, shift_code = :shift, status = :status, deactivated_by = :deact,
                    hr_version = :hv, hr_digest = :hd, hr_source_ts = :hts,
                    pickup_point_id = :pickup, special_assistance = :assist, admin_version = :av,
                    pickup_needs_review = :review, updated_at = now()
                WHERE tenant_id = :t AND employee_id = :id""")
                .params(params(e))
                .update();
    }

    private static java.util.Map<String, Object> params(EmployeeState e) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("id", e.employeeId());
        p.put("t", e.tenantId().value());
        p.put("ext", e.externalId());
        p.put("name", e.hr().name());
        p.put("email", e.hr().email());
        p.put("phone", e.hr().phone());
        p.put("mgr", e.hr().managerExternalId());
        p.put("office", e.hr().officeCode());
        p.put("shift", e.hr().shiftCode());
        p.put("status", e.status().name());
        p.put("deact", e.deactivatedBy() == null ? null : e.deactivatedBy().name());
        p.put("hv", e.hrVersion());
        p.put("hd", e.hrDigest());
        p.put("hts", Sql.ts(e.hrSourceTs()));
        p.put("bucket", e.bucket());
        p.put("pickup", e.pickupPointId());
        p.put("assist", e.specialAssistance());
        p.put("av", e.adminVersion());
        p.put("review", e.pickupNeedsReview());
        return p;
    }
}
