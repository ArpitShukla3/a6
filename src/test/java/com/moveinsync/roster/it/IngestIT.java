package com.moveinsync.roster.it;

import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordType;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.TransportProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestIT extends AbstractIT {

    @Autowired TransportProfileService transport;
    @Autowired BookingService bookings;

    private Map<String, Object> row(String ext) {
        return jdbc.queryForMap("SELECT * FROM employee WHERE tenant_id = ? AND external_id = ?", ACME.value(), ext);
    }

    @Test
    void briefExample_transferV7BeforeShiftChangeV6() {
        ingest(ACME, create("E1", 5, "BLR-01", "S1"));
        var r = ingest(ACME,
                update("E1", 7, "BLR-02", "S3"),   // office transfer, carries the v7 shift
                update("E1", 6, "BLR-01", "S2"));  // older shift change arrives late
        assertThat(r.results()).extracting(x -> x.outcome()).containsExactly(Outcome.APPLIED, Outcome.STALE);
        var e = row("E1");
        assertThat(e.get("office_code")).isEqualTo("BLR-02");
        assertThat(e.get("shift_code")).isEqualTo("S3");
        assertThat(e.get("hr_version")).isEqualTo(7L);
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        UUID batchId = UUID.randomUUID();
        List<IncomingRecord> feed = List.of(
                create("E1", 1, "BLR-01", "S1"), create("E2", 1, "BLR-01", "S1"),
                update("E1", 2, "BLR-02", "S1"), update("E1", 2, "BLR-02", "S1"),
                rec(RecordType.UPDATE, "E3", 1, "NOPE-99", "S1"),     // rejected
                deactivate("E2", 2));
        ingest(ACME, batchId, feed);
        List<Map<String, Object>> employeesBefore = jdbc.queryForList("SELECT * FROM employee ORDER BY external_id");
        int outboxBefore = count("SELECT count(*) FROM outbox_event");
        int rejectionsBefore = count("SELECT count(*) FROM ingest_rejection");

        var second = ingest(ACME, batchId, feed);

        assertThat(jdbc.queryForList("SELECT * FROM employee ORDER BY external_id")).isEqualTo(employeesBefore);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(outboxBefore);
        assertThat(count("SELECT count(*) FROM ingest_rejection")).isEqualTo(rejectionsBefore);
        assertThat(second.summary().get(Outcome.APPLIED)).isZero();
        // v2 records are duplicates; the v1 creates are now older than stored v2, so stale
        assertThat(second.summary().get(Outcome.DUPLICATE)).isEqualTo(3);
        assertThat(second.summary().get(Outcome.STALE)).isEqualTo(2);
        assertThat(second.summary().get(Outcome.REJECTED)).isEqualTo(1);
    }

    @Test
    void oneBadRecordNeverFailsTheBatch() {
        var bad = new IncomingRecord(RecordType.CREATE, "E2", 1L, NOW, "B", "b@acme.test", "12-bad", null, "BLR-01", "S1", Set.of());
        var r = ingest(ACME, create("E1", 1, "BLR-01", "S1"), bad, null, create("E3", 1, "BLR-01", "S1"));
        assertThat(r.results()).extracting(x -> x.outcome())
                .containsExactly(Outcome.APPLIED, Outcome.REJECTED, Outcome.REJECTED, Outcome.APPLIED);
        assertThat(r.results().get(1).errors()).extracting(e -> e.code()).containsExactly("PHONE_INVALID");
        assertThat(count("SELECT count(*) FROM employee")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ingest_rejection WHERE external_id = 'E2' AND field = 'phone'")).isEqualTo(1);
    }

    @Test
    void sameVersionDifferentDataIsAConflict() {
        ingest(ACME, create("E1", 3, "BLR-01", "S1"));
        var r = ingest(ACME, update("E1", 3, "BLR-01", "S9"));
        assertThat(r.results().get(0).outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(row("E1").get("shift_code")).isEqualTo("S1");
    }

    @Test
    void hrUpdateNeverOverwritesTransportOwnedFields() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        transport.update(ACME, "E1", 0L, "PP-42", "WHEELCHAIR");
        var hrWithTransport = new IncomingRecord(RecordType.UPDATE, "E1", 2L, NOW, "Name E1", "e1@acme.test", null, null,
                "BLR-01", "S2", Set.of("pickupPointId", "specialAssistance"));
        var r = ingest(ACME, hrWithTransport);
        assertThat(r.results().get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.results().get(0).warnings()).extracting(w -> w.code()).containsOnly("FIELD_OWNED_BY_TRANSPORT");
        var e = row("E1");
        assertThat(e.get("pickup_point_id")).isEqualTo("PP-42");
        assertThat(e.get("special_assistance")).isEqualTo("WHEELCHAIR");
        assertThat(e.get("shift_code")).isEqualTo("S2");
    }

    @Test
    void officeTransferKeepsPickupButFlagsItForReview() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        transport.update(ACME, "E1", 0L, "PP-42", null);
        ingest(ACME, update("E1", 2, "HYD-01", "S1"));
        var e = row("E1");
        assertThat(e.get("pickup_point_id")).isEqualTo("PP-42");
        assertThat(e.get("pickup_needs_review")).isEqualTo(true);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'PICKUP_REVIEW_REQUIRED'")).isEqualTo(1);
        // admin confirms the pickup point -> flag cleared
        transport.update(ACME, "E1", 1L, "PP-77", null);
        assertThat(row("E1").get("pickup_needs_review")).isEqualTo(false);
    }

    @Test
    void everyAppliedChangeHasExactlyOneOutboxEventInTheSameTransaction() {
        var r = ingest(ACME, create("E1", 1, "BLR-01", "S1"), update("E1", 2, "BLR-02", "S1"),
                update("E1", 1, "BLR-01", "S1"), update("E1", 2, "BLR-02", "S1"));
        assertThat(r.summary().get(Outcome.APPLIED)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY id", String.class))
                .containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED");
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_event ORDER BY id DESC LIMIT 1", String.class);
        assertThat(payload).contains("\"changedFields\": [\"officeCode\"]").doesNotContain("Name E1", "@acme.test");
    }

    @Test
    void deactivationCancelsFutureBookingsAndFreesSeats() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        var b1 = bookings.create(ACME, "E1", Instant.now().plusSeconds(3600));
        var b2 = bookings.create(ACME, "E1", Instant.now().plusSeconds(7200));
        UUID employeeId = b1.employeeId();
        assertThat(count("SELECT count(*) FROM seat WHERE employee_id = ?", employeeId)).isEqualTo(2);

        var r = ingest(ACME, deactivate("E1", 2));

        assertThat(r.results().get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(jdbc.queryForList("SELECT status FROM booking WHERE booking_id IN (?, ?)", String.class, b1.bookingId(), b2.bookingId()))
                .containsOnly("CANCELLED");
        assertThat(count("SELECT count(*) FROM seat WHERE employee_id = ?", employeeId)).isZero();
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'BOOKING_CANCELLED'")).isEqualTo(2);
        // replay: nothing more to cancel, no extra events
        ingest(ACME, deactivate("E1", 2));
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'BOOKING_CANCELLED'")).isEqualTo(2);
    }

    @Test
    void deactivateBeforeCreateLeavesTombstoneSoLateCreateIsStale() {
        var r = ingest(ACME, deactivate("E9", 5), create("E9", 4, "BLR-01", "S1"));
        assertThat(r.results()).extracting(x -> x.outcome()).containsExactly(Outcome.APPLIED, Outcome.STALE);
        assertThat(row("E9").get("status")).isEqualTo("INACTIVE");
    }

    @Test
    void rehireNeedsNewerCreate() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"), deactivate("E1", 2));
        ingest(ACME, update("E1", 3, "BLR-02", "S1"));
        assertThat(row("E1").get("status")).isEqualTo("INACTIVE");
        ingest(ACME, create("E1", 4, "BLR-02", "S1"));
        assertThat(row("E1").get("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY id", String.class))
                .containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_DEACTIVATED", "EMPLOYEE_UPDATED", "EMPLOYEE_REACTIVATED");
    }

    @Test
    void tenantsAreIsolatedInTheStore() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        var r = ingest(GLOBEX, create("E1", 1, "BLR-01", "S1"));
        assertThat(r.results().get(0).outcome()).isEqualTo(Outcome.APPLIED); // same HR id, different tenant
        assertThat(count("SELECT count(*) FROM employee WHERE external_id = 'E1'")).isEqualTo(2);
    }

    @Test
    void largeBatchIsProcessedRecordByRecord() {
        List<IncomingRecord> recs = new ArrayList<>();
        for (int i = 0; i < 300; i++) recs.add(create("B" + i, 1, i % 50 == 0 ? "BAD-1" : "BLR-01", "S1"));
        var r = ingest(ACME, UUID.randomUUID(), recs);
        assertThat(r.summary().get(Outcome.APPLIED)).isEqualTo(294);
        assertThat(r.summary().get(Outcome.REJECTED)).isEqualTo(6);
    }
}
