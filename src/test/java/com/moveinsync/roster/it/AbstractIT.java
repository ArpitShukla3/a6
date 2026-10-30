package com.moveinsync.roster.it;

import com.moveinsync.roster.domain.model.TenantId;
import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordType;
import com.moveinsync.roster.fault.FaultInjector;
import com.moveinsync.roster.outbox.ChangeEventPublisher;
import com.moveinsync.roster.outbox.OutboxRelay;
import com.moveinsync.roster.service.IngestBatchService;
import com.moveinsync.roster.service.BatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One PostgreSQL container for the whole test run. Relay and async reconciliation are switched
 * off so tests drive them step by step.
 */
@SpringBootTest(properties = {
        "roster.reconciliation.async=false",
        "roster.outbox.relay-enabled=false",
        "roster.monitor.interval-ms=600000",
        "roster.buckets=64",
        "spring.profiles.active=plainlogs"})
@Import(AbstractIT.TestBeans.class)
public abstract class AbstractIT {

    public static final TenantId ACME = TenantId.of("11111111-1111-1111-1111-111111111111");
    public static final TenantId GLOBEX = TenantId.of("22222222-2222-2222-2222-222222222222");

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("roster.reconciliation.data-dir", () -> System.getProperty("java.io.tmpdir") + "/roster-it-" + UUID.randomUUID());
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected IngestBatchService batch;
    @Autowired protected OutboxRelay relay;
    @Autowired protected RecordingPublisher publisher;
    @Autowired protected FaultInjector faults;
    @Autowired protected JwtEncoder jwtEncoder;
    @Autowired protected ChangeEventPublisher activePublisher;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE booking, reconciliation_candidate, reconciliation_run, ingest_rejection, outbox_event, employee CASCADE");
        jdbc.execute("UPDATE seat SET employee_id = NULL");
        jdbc.execute("UPDATE tenant SET brake_missing_ratio = NULL");
        publisher.reset();
        faults.disarmAll();
    }

    // ---------------------------------------------------------------- helpers

    protected static final Instant NOW = Instant.now();

    protected static IncomingRecord create(String ext, long v, String office, String shift) {
        return rec(RecordType.CREATE, ext, v, office, shift);
    }

    protected static IncomingRecord update(String ext, long v, String office, String shift) {
        return rec(RecordType.UPDATE, ext, v, office, shift);
    }

    protected static IncomingRecord deactivate(String ext, long v) {
        return new IncomingRecord(RecordType.DEACTIVATE, ext, v, NOW.minusSeconds(60), null, null, null, null, null, null, Set.of());
    }

    protected static IncomingRecord rec(RecordType type, String ext, long v, String office, String shift) {
        return new IncomingRecord(type, ext, v, NOW.minusSeconds(60), "Name " + ext, ext.toLowerCase() + "@acme.test",
                "+919876500000", null, office, shift, Set.of());
    }

    protected BatchResult ingest(TenantId tenant, IncomingRecord... records) {
        return batch.ingest(tenant, UUID.randomUUID(), java.util.Arrays.asList(records));
    }

    protected BatchResult ingest(TenantId tenant, UUID batchId, List<IncomingRecord> records) {
        return batch.ingest(tenant, batchId, records);
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected String token(TenantId tenant, String subject, String employeeId, String... roles) {
        JwtClaimsSet.Builder c = JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim("tenant_id", tenant.toString()).claim("roles", List.of(roles));
        if (employeeId != null) {
            c.claim("employee_id", employeeId);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), c.build())).getTokenValue();
    }

    protected int drainOutbox() {
        int total = 0;
        int n;
        while ((n = relay.relayOnce()) > 0) {
            total += n;
        }
        return total;
    }
}
