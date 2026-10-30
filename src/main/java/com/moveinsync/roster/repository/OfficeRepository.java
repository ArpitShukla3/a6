package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.model.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Repository
public class OfficeRepository {

    private final JdbcClient jdbc;

    public OfficeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> codes(TenantId tenant) {
        return new HashSet<>(jdbc.sql("SELECT office_code FROM office WHERE tenant_id = :t")
                .param("t", tenant.value()).query(String.class).list());
    }

    public boolean insert(TenantId tenant, String code) {
        return jdbc.sql("INSERT INTO office (tenant_id, office_code) VALUES (:t, :c) ON CONFLICT DO NOTHING")
                .param("t", tenant.value()).param("c", code).update() == 1;
    }

    public Optional<Double> brakeMissingRatio(TenantId tenant) {
        java.util.List<Double> r = jdbc.sql("SELECT brake_missing_ratio FROM tenant WHERE tenant_id = :t")
                .param("t", tenant.value())
                .query((rs, n) -> rs.getObject(1) == null ? null : rs.getDouble(1))
                .list();
        return r.isEmpty() ? Optional.empty() : Optional.ofNullable(r.get(0));
    }
}
