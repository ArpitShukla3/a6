package com.moveinsync.roster.bench;

import com.moveinsync.roster.domain.model.HrProfile;
import com.moveinsync.roster.domain.model.RecordHasher;
import com.moveinsync.roster.it.AbstractIT;
import com.moveinsync.roster.repository.ReconciliationRun;
import com.moveinsync.roster.service.ReconciliationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 200,000-employee nightly reconciliation. Run with: ./scripts/mvn.sh test -Pbench  (JVM -Xmx256m).
 * The heap limit covers the whole Spring application, so "bounded memory" is enforced, not just measured.
 */
@Tag("bench")
@TestPropertySource(properties = "roster.buckets=256")
class SnapshotDiffBenchmark extends AbstractIT {

    static final int N = 200_000;

    @Autowired ReconciliationService recon;
    @Autowired RecordHasher hasher;

    static HrProfile hr(int i, String shift) {
        return new HrProfile("Employee " + i, "e" + i + "@acme.test", "+919876500000", null, "BLR-01", shift);
    }

    private void seed() {
        Timestamp yesterday = Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS));
        List<Object[]> batch = new ArrayList<>(10_000);
        for (int i = 0; i < N; i++) {
            String ext = "E" + i;
            HrProfile h = hr(i, "S1");
            batch.add(new Object[]{UUID.randomUUID(), ACME.value(), ext, h.name(), h.email(), h.phone(), h.officeCode(),
                    h.shiftCode(), hasher.digest(ext, h), yesterday, hasher.bucket(ext)});
            if (batch.size() == 10_000 || i == N - 1) {
                jdbc.batchUpdate("""
                        INSERT INTO employee (employee_id, tenant_id, external_id, name, email, phone, office_code, shift_code,
                            status, hr_version, hr_digest, hr_source_ts, bucket)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?)""", batch);
                batch.clear();
            }
        }
    }

    /** rows [0, upTo): 1% changed, 0.5% missing, plus 0.5% new. */
    private Path writeFile(int upTo, boolean complete) throws Exception {
        Path f = Files.createTempFile("snapshot", ".csv");
        int written = 0;
        try (BufferedWriter w = Files.newBufferedWriter(f)) {
            w.write("#ROSTER_EXPORT,as_of=" + Instant.now() + ",expected_rows=" + N + "\n");
            w.write("external_id,source_version,name,email,phone,manager_external_id,office_code,shift_code\n");
            for (int i = 0; i < upTo; i++) {
                if (i % 200 == 7) continue;                         // 0.5% missing
                boolean changed = i % 100 == 3;                     // 1% changed
                HrProfile h = hr(i, changed ? "S2" : "S1");
                w.write("E" + i + "," + (changed ? 2 : 1) + "," + h.name() + "," + h.email() + "," + h.phone() + ",,"
                        + h.officeCode() + "," + h.shiftCode() + "\n");
                written++;
            }
            if (complete) {
                for (int i = 0; i < 1_000; i++) {                  // new joiners keep the row count at N
                    w.write("N" + i + ",1,New " + i + ",n" + i + "@acme.test,,,BLR-01,S1\n");
                    written++;
                }
                w.write("#END,rows=" + written + "\n");
            }
        }
        return f;
    }

    private ReconciliationRun run(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return recon.find(ACME, recon.submit(ACME, in).runId());
        }
    }

    @Test
    void diff200kRowsUnder60sWithBoundedMemory() throws Exception {
        seed();
        Path file = writeFile(N, true);

        BenchReport.resetPeaks();
        long t0 = System.nanoTime();
        ReconciliationRun r = run(file);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        long peakMb = BenchReport.peakHeapMb();

        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.rowsRead()).isEqualTo(N);
        assertThat(r.toUpdate()).isEqualTo(2_000);
        assertThat(r.toCreate()).isEqualTo(1_000);
        assertThat(r.toDeactivate()).isEqualTo(1_000);
        assertThat(r.diffMillis()).isLessThan(60_000);

        BenchReport.write("""
                ## Nightly reconciliation, %,d-row snapshot vs %,d stored employees
                %s

                | Phase | Result |
                |---|---|
                | Diff (stream + bucket spill + digests + drill-down) | %,d ms |
                | Total incl. applying 2,000 updates, 1,000 creates, 1,000 deactivations | %,d ms |
                | Peak heap (whole JVM, -Xmx%d MB) | %d MB |
                | File size | %.1f MB |
                """.formatted(N, N, BenchReport.machine(), r.diffMillis(), totalMs,
                Runtime.getRuntime().maxMemory() / (1024 * 1024), peakMb, Files.size(file) / 1e6));
        Files.deleteIfExists(file);
    }

    @Test
    void briefExample_120kOf200kFileIsHeld() throws Exception {
        seed();
        Path file = writeFile(120_000, false);
        long t0 = System.nanoTime();
        ReconciliationRun r = run(file);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.status()).isEqualTo("HELD");
        assertThat(count("SELECT count(*) FROM employee WHERE status = 'INACTIVE'")).isZero();
        BenchReport.write("""
                ## Truncated export (cut at row 120,000 of 200,000)
                status=%s, reasons=%s, rowsRead=%,d, expectedRows=%,d, appearsMissing=%,d, missingRatio=%.3f, deactivated=0, time=%,d ms
                """.formatted(r.status(), r.brakeReasons(), r.rowsRead(), r.expectedRows(), r.toDeactivate(), r.missingRatio(), ms));
        Files.deleteIfExists(file);
    }
}
