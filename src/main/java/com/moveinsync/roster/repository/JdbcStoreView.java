package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.domain.reconcile.BucketDigests;
import com.moveinsync.roster.domain.reconcile.StoreView;
import com.moveinsync.roster.domain.reconcile.StoredRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SQL implementation of the diff's view of the store. Digests are aggregated in Java from a
 * streamed cursor (fetch size 5000), so Java and the database never have to agree on a hash
 * function: the stored hr_digest values were computed by the same Java code.
 */
public class JdbcStoreView implements StoreView {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final TenantId tenant;
    private final int buckets;

    public JdbcStoreView(JdbcTemplate jdbc, TransactionTemplate readTx, TenantId tenant, int buckets) {
        this.jdbc = jdbc;
        this.readTx = readTx;
        this.tenant = tenant;
        this.buckets = buckets;
    }

    @Override
    public BucketDigests activeDigests() {
        BucketDigests d = new BucketDigests(buckets);
        // A transaction is required for the PostgreSQL driver to use a cursor instead of loading all rows.
        readTx.executeWithoutResult(s -> jdbc.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT bucket, hr_digest FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'");
            ps.setObject(1, tenant.value());
            ps.setFetchSize(5000);
            return ps;
        }, rs -> {
            d.add(rs.getInt(1), rs.getLong(2));
        }));
        return d;
    }

    @Override
    public List<StoredRow> loadBucket(int bucket) {
        return jdbc.query("""
                SELECT employee_id, external_id, hr_version, hr_digest, status, deactivated_by, hr_source_ts
                FROM employee WHERE tenant_id = ? AND bucket = ?""",
                (rs, n) -> new StoredRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        EmployeeStatus.valueOf(rs.getString(5)),
                        rs.getString(6) == null ? null : DeactivatedBy.valueOf(rs.getString(6)),
                        Sql.instant(rs, "hr_source_ts")),
                tenant.value(), bucket);
    }
}
