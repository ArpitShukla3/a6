package com.moveinsync.roster.it;

import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.error.ConflictException;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.RecordResult;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIT extends AbstractIT {

    @Autowired BookingService bookings;

    @Test
    void concurrentOutOfOrderUpdatesForOneEmployeeConvergeToHighestVersion() throws Exception {
        List<Integer> versions = new ArrayList<>();
        for (int v = 1; v <= 50; v++) versions.add(v);
        Collections.shuffle(versions);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RecordResult>> futures = new ArrayList<>();
        for (int v : versions) {
            futures.add(pool.submit(() -> {
                start.await();
                // create for v=1 races the inserts; others are updates
                var r = v == 1 ? create("C1", 1, "BLR-01", "S1") : update("C1", v, "BLR-01", "S" + v);
                return ingest(ACME, r).results().get(0);
            }));
        }
        start.countDown();
        int applied = 0;
        for (Future<RecordResult> f : futures) {
            RecordResult r = f.get();
            assertThat(r.outcome()).isIn(Outcome.APPLIED, Outcome.STALE);
            if (r.outcome() == Outcome.APPLIED) applied++;
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT hr_version FROM employee WHERE external_id = 'C1'", Long.class)).isEqualTo(50L);
        assertThat(jdbc.queryForObject("SELECT shift_code FROM employee WHERE external_id = 'C1'", String.class)).isEqualTo("S50");
        assertThat(count("SELECT count(*) FROM employee WHERE external_id = 'C1'")).isEqualTo(1);
        // exactly one outbox event per applied change, in strictly increasing version order
        List<Long> eventVersions = jdbc.queryForList(
                "SELECT (payload->>'hrVersion')::bigint FROM outbox_event WHERE event_type LIKE 'EMPLOYEE_%' ORDER BY id", Long.class);
        assertThat(eventVersions).hasSize(applied).isSorted().doesNotHaveDuplicates();
        assertThat(eventVersions.get(eventVersions.size() - 1)).isEqualTo(50L);
    }

    @Test
    void concurrentCreatesOfTheSameNewEmployeeInsertOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return ingest(ACME, create("NEW1", 1, "BLR-01", "S1")).results().get(0).outcome();
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (var f : fs) outcomes.add(f.get());
        pool.shutdown();
        assertThat(outcomes).filteredOn(o -> o == Outcome.APPLIED).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.DUPLICATE).hasSize(7);
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
    }

    /** No interleaving may leave a confirmed future booking (or a held seat) for an exited employee. */
    @RepeatedTest(5)
    void bookingRacingDeactivationNeverLeavesALiveBooking() throws Exception {
        ingest(ACME, create("R1", 1, "BLR-01", "S1"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fs.add(pool.submit((Callable<Void>) () -> {
                start.await();
                try {
                    bookings.create(ACME, "R1", Instant.now().plusSeconds(3600));
                } catch (ConflictException expectedAfterDeactivation) {
                    // EMPLOYEE_INACTIVE or NO_SEAT_AVAILABLE
                }
                return null;
            }));
        }
        fs.add(pool.submit((Callable<Void>) () -> {
            start.await();
            ingest(ACME, deactivate("R1", 2));
            return null;
        }));
        start.countDown();
        for (var f : fs) f.get();
        pool.shutdown();

        UUID id = jdbc.queryForObject("SELECT employee_id FROM employee WHERE external_id = 'R1'", UUID.class);
        assertThat(count("SELECT count(*) FROM booking WHERE employee_id = ? AND status = 'CONFIRMED'", id)).isZero();
        assertThat(count("SELECT count(*) FROM seat WHERE employee_id = ?", id)).isZero();
    }
}
