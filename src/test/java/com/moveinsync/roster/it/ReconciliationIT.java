package com.moveinsync.roster.it;

import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordType;
import com.moveinsync.roster.error.ConflictException;
import com.moveinsync.roster.fault.FaultPoint;
import com.moveinsync.roster.repository.ReconciliationRun;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationIT extends AbstractIT {

    @Autowired ReconciliationService recon;
    @Autowired BookingService bookings;

    static final Instant AS_OF = NOW;

    // ---------------------------------------------------------------- helpers

    private void seed(int n) {
        List<IncomingRecord> recs = new ArrayList<>();
        for (int i = 0; i < n; i++) recs.add(create("E" + i, 1, "BLR-01", "S1"));
        for (int from = 0; from < n; from += 1000) {
            ingest(ACME, UUID.randomUUID(), recs.subList(from, Math.min(n, from + 1000)));
        }
    }

    /** CSV row matching what the seed stored (so unchanged rows hash identically). */
    static String row(String ext, long v, String office, String shift) {
        return String.join(",", ext, Long.toString(v), "Name " + ext, ext.toLowerCase() + "@acme.test",
                "+919876500000", "", office, shift);
    }

    static String file(List<String> rows, Integer expected, boolean trailer) {
        StringBuilder sb = new StringBuilder("#ROSTER_EXPORT,as_of=" + AS_OF + (expected == null ? "" : ",expected_rows=" + expected) + "\n");
        sb.append("external_id,source_version,name,email,phone,manager_external_id,office_code,shift_code\n");
        rows.forEach(r -> sb.append(r).append('\n'));
        if (trailer) sb.append("#END,rows=").append(rows.size()).append('\n');
        return sb.toString();
    }

    static List<String> rows(int n, Function<Integer, String> f) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String r = f.apply(i);
            if (r != null) out.add(r);
        }
        return out;
    }

    private ReconciliationRun submit(String csv) {
        var run = recon.submit(ACME, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        return recon.find(ACME, run.runId());
    }

    private String status(String ext) {
        return jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND external_id = ?", String.class, ACME.value(), ext);
    }

    private int inactive() {
        return count("SELECT count(*) FROM employee WHERE status = 'INACTIVE'");
    }

    // ---------------------------------------------------------------- tests

    @Test
    void normalNight_appliesNewChangedAndMissing_andCancelsBookingsOfLeavers() {
        seed(100);
        var booking = bookings.create(ACME, "E7", Instant.now().plusSeconds(3600));
        int eventsBefore = count("SELECT count(*) FROM outbox_event");
        List<String> rows = rows(100, i -> i == 7 || i == 8 ? null : row("E" + i, i == 5 ? 2 : 1, "BLR-01", i == 5 ? "S2" : "S1"));
        rows.add(row("N1", 1, "BLR-02", "S1"));

        var run = submit(file(rows, rows.size(), true));

        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.toCreate()).isEqualTo(1);
        assertThat(run.toUpdate()).isEqualTo(1);
        assertThat(run.toDeactivate()).isEqualTo(2);
        assertThat(run.deactivated()).isEqualTo(2);
        assertThat(status("E7")).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT deactivated_by FROM employee WHERE external_id='E7'", String.class)).isEqualTo("RECONCILIATION");
        assertThat(jdbc.queryForObject("SELECT shift_code FROM employee WHERE external_id='E5'", String.class)).isEqualTo("S2");
        assertThat(status("N1")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE booking_id = ?", String.class, booking.bookingId())).isEqualTo("CANCELLED");
        // events only for actual changes: created, updated, 2 deactivated, 1 booking cancelled
        assertThat(count("SELECT count(*) FROM outbox_event") - eventsBefore).isEqualTo(5);
    }

    @Test
    void briefExample_fileCutOff_brakeStopsRun_nobodyDeactivated_operatorSeesMissingCount() {
        seed(2000);
        int eventsBefore = count("SELECT count(*) FROM outbox_event");
        // export of 2000 cut off after 1200 rows: header promises 2000, no trailer
        var run = submit(file(rows(1200, i -> row("E" + i, 1, "BLR-01", "S1")), 2000, false));

        assertThat(run.status()).isEqualTo("HELD");
        assertThat(run.brakeReasons()).isEqualTo("MISSING_RATIO,TRUNCATED_FILE");
        assertThat(run.toDeactivate()).isEqualTo(800);
        assertThat(run.missingRatio()).isEqualTo(0.4);
        assertThat(run.expectedRows() - run.rowsRead()).isEqualTo(800);
        assertThat(inactive()).isZero();
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(eventsBefore);
        assertThat(recon.sampleMissing(run.runId())).hasSize(20).allMatch(id -> id.startsWith("E"));

        var rejected = recon.reject(ACME, run.runId(), "oncall-1");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(inactive()).isZero();
    }

    @Test
    void operatorApprovalAppliesHeldDeactivations_exceptEmployeesChangedSinceTheDiff() {
        seed(10);
        var run = submit(file(rows(10, i -> i < 6 ? row("E" + i, 1, "BLR-01", "S1") : null), 6, true));
        assertThat(run.status()).isEqualTo("HELD");
        assertThat(run.brakeReasons()).isEqualTo("MISSING_RATIO");
        assertThat(inactive()).isZero();

        ingest(ACME, update("E9", 2, "BLR-02", "S1"));            // HR touched E9 after the diff

        var approved = recon.approve(ACME, run.runId(), "oncall-1");
        var done = recon.find(ACME, approved.runId());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.decidedBy()).isEqualTo("oncall-1");
        assertThat(done.deactivated()).isEqualTo(3);
        assertThat(status("E6")).isEqualTo("INACTIVE");
        assertThat(status("E9")).isEqualTo("ACTIVE");
    }

    @Test
    void employeeCreatedAfterExportWasCutIsNotDeactivated() {
        seed(10);
        ingest(ACME, new IncomingRecord(RecordType.CREATE, "LATE", 1L, AS_OF.plusSeconds(120), "Late", "late@acme.test",
                null, null, "BLR-01", "S1", Set.of()));
        var run = submit(file(rows(10, i -> row("E" + i, 1, "BLR-01", "S1")), 10, true));
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.toDeactivate()).isZero();
        assertThat(status("LATE")).isEqualTo("ACTIVE");
    }

    @Test
    void badRowsForKnownEmployeesAreNotTreatedAsMissing() {
        seed(100);
        // 3 rows with a malformed phone (3% < 5% rejected threshold)
        var run = submit(file(rows(100, i -> i < 3
                ? String.join(",", "E" + i, "2", "Name", "e@acme.test", "12-bad", "", "BLR-01", "S1")
                : row("E" + i, 1, "BLR-01", "S1")), 100, true));
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.rowsRejected()).isEqualTo(3);
        assertThat(run.toDeactivate()).isZero();
        assertThat(inactive()).isZero();
        assertThat(count("SELECT count(*) FROM ingest_rejection WHERE ref_id = ? AND error_code = 'PHONE_INVALID'", run.runId())).isEqualTo(3);
    }

    @Test
    void tooManyBadRowsTripTheBrake() {
        seed(100);
        var run = submit(file(rows(100, i -> i < 10
                ? String.join(",", "E" + i, "2", "Name", "e@acme.test", "", "", "NOPE-1", "S1")
                : row("E" + i, 1, "BLR-01", "S1")), 100, true));
        assertThat(run.status()).isEqualTo("HELD");
        assertThat(run.brakeReasons()).isEqualTo("REJECTED_RATIO");
    }

    @Test
    void snapshotCannotRollBackNewerRealtimeData() {
        seed(5);
        ingest(ACME, update("E1", 5, "HYD-01", "S9"));                // newer real-time change
        var run = submit(file(rows(5, i -> row("E" + i, i == 1 ? 4 : 1, "BLR-01", "S1")), 5, true));
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.stale()).isEqualTo(1);
        var e1 = jdbc.queryForMap("SELECT office_code, hr_version FROM employee WHERE external_id='E1'");
        assertThat(e1).containsEntry("office_code", "HYD-01").containsEntry("hr_version", 5L);
    }

    @Test
    void employeeDeactivatedByReconciliationComesBackWhenSeenAgain() {
        seed(10);
        submit(file(rows(10, i -> i == 3 ? null : row("E" + i, 1, "BLR-01", "S1")), 9, true));
        assertThat(status("E3")).isEqualTo("INACTIVE");
        var run2 = submit(file(rows(10, i -> row("E" + i, 1, "BLR-01", "S1")), 10, true));
        assertThat(run2.status()).isEqualTo("COMPLETED");
        assertThat(status("E3")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'EMPLOYEE_REACTIVATED'")).isEqualTo(1);
    }

    @Test
    void replayingTheSameFileChangesNothing() {
        seed(50);
        String csv = file(rows(52, i -> i == 4 ? null : row("E" + i, i == 9 ? 2 : 1, "BLR-01", i == 9 ? "S2" : "S1")), 51, true);
        submit(csv);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM employee ORDER BY external_id");
        int events = count("SELECT count(*) FROM outbox_event");

        var second = submit(csv);

        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(second.applied()).isZero();
        assertThat(second.deactivated()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM employee ORDER BY external_id")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(events);
    }

    @Test
    void crashMidApply_retryCompletesWithoutDuplicateEvents() {
        seed(20);
        List<String> rows = rows(20, i -> i >= 18 ? null : row("E" + i, i < 6 ? 2 : 1, "BLR-01", i < 6 ? "S2" : "S1"));
        faults.arm(FaultPoint.RECON_APPLY_STEP, 3);                  // dies after 3 changes
        var failed = submit(file(rows, 18, true));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("APPLY_FAILED");

        recon.retry(ACME, failed.runId(), "oncall-1");
        var done = recon.find(ACME, failed.runId());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM employee WHERE shift_code = 'S2'")).isEqualTo(6);
        assertThat(inactive()).isEqualTo(2);
        // one event per change overall: 20 creates + 6 updates + 2 deactivations
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(28);
    }

    @Test
    void thresholdIsConfigurablePerTenant() {
        seed(20);
        jdbc.update("UPDATE tenant SET brake_missing_ratio = 0.10 WHERE tenant_id = ?", ACME.value());
        var run = submit(file(rows(20, i -> i < 17 ? row("E" + i, 1, "BLR-01", "S1") : null), 17, true)); // 15% missing
        assertThat(run.status()).isEqualTo("HELD");
        assertThat(run.threshold()).isEqualTo(0.10);
    }

    @Test
    void fileWithoutHeaderFails() {
        var run = submit("external_id,source_version\nE1,1\n");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("SNAPSHOT_HEADER_MISSING");
    }

    @Test
    void onlyOneRunPerTenantInFlight() {
        jdbc.update("INSERT INTO reconciliation_run (run_id, tenant_id, status, file_path) VALUES (?, ?, 'DIFFING', '/tmp/x')",
                UUID.randomUUID(), ACME.value());
        assertThatThrownBy(() -> submit(file(List.of(), 0, true)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already in progress");
    }
}
