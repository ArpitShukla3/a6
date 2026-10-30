package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.model.HrProfile;
import com.moveinsync.roster.domain.model.RecordHasher;
import com.moveinsync.roster.domain.reconcile.BucketDigests;
import com.moveinsync.roster.domain.reconcile.BucketSpill;
import com.moveinsync.roster.domain.reconcile.BucketedSnapshotDiffer;
import com.moveinsync.roster.domain.reconcile.DiffSink;
import com.moveinsync.roster.domain.reconcile.SpilledRow;
import com.moveinsync.roster.domain.reconcile.StoreView;
import com.moveinsync.roster.domain.reconcile.StoredRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The diff runs against an in-memory store: no database, no Spring. */
class BucketedSnapshotDifferTest {

    private static final RecordHasher H = new RecordHasher(16);
    private static final Instant AS_OF = Instant.parse("2026-10-29T00:00:00Z");
    private static final Instant BEFORE = AS_OF.minusSeconds(3600);

    @TempDir
    Path tmp;

    private final BucketedSnapshotDiffer differ = new BucketedSnapshotDiffer();

    static HrProfile hr(String name) {
        return new HrProfile(name, name.toLowerCase() + "@x.co", null, null, "BLR-01", "S1");
    }

    static final class MemStore implements StoreView {
        final List<StoredRow> rows = new ArrayList<>();

        void add(String ext, HrProfile hr, EmployeeStatus status, Instant ts) {
            rows.add(new StoredRow(UUID.randomUUID(), ext, 1, H.digest(ext, hr), status,
                    status == EmployeeStatus.INACTIVE ? DeactivatedBy.RECONCILIATION : null, ts));
        }

        @Override
        public BucketDigests activeDigests() {
            BucketDigests d = new BucketDigests(H.bucketCount());
            rows.stream().filter(StoredRow::isActive).forEach(r -> d.add(H.bucket(r.externalId()), r.hrDigest()));
            return d;
        }

        @Override
        public List<StoredRow> loadBucket(int bucket) {
            return rows.stream().filter(r -> H.bucket(r.externalId()) == bucket).toList();
        }
    }

    static final class Recorder implements DiffSink {
        final List<String> news = new ArrayList<>(), changed = new ArrayList<>(), missing = new ArrayList<>(), dupes = new ArrayList<>();

        public void onNew(SpilledRow r) { news.add(r.externalId()); }
        public void onChanged(SpilledRow r, StoredRow s) { changed.add(r.externalId()); }
        public void onMissing(StoredRow s) { missing.add(s.externalId()); }
        public void onDuplicateRow(SpilledRow r) { dupes.add(r.externalId()); }
    }

    private Recorder run(MemStore store, List<SpilledRow> fileRows) {
        try (BucketSpill spill = new BucketSpill(tmp.resolve("s" + System.nanoTime()), H)) {
            fileRows.forEach(spill::add);
            spill.finishWriting();
            Recorder rec = new Recorder();
            differ.drill(differ.differingBuckets(spill.digests(), store.activeDigests()), spill, store, AS_OF, rec);
            return rec;
        }
    }

    private static SpilledRow row(int i, String ext, HrProfile hr) {
        return new SpilledRow(i, ext, 2, H.digest(ext, hr), false, hr);
    }

    @Test
    void findsNewChangedAndMissing() {
        MemStore store = new MemStore();
        for (int i = 0; i < 200; i++) store.add("E" + i, hr("N" + i), EmployeeStatus.ACTIVE, BEFORE);
        List<SpilledRow> file = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            if (i == 7) continue;                                     // missing
            file.add(row(i, "E" + i, hr(i == 3 ? "Changed" : "N" + i)));// one changed
        }
        file.add(row(500, "E500", hr("New")));                         // new
        Recorder r = run(store, file);
        assertThat(r.news).containsExactly("E500");
        assertThat(r.changed).containsExactly("E3");
        assertThat(r.missing).containsExactly("E7");
    }

    @Test
    void identicalDataDiffsToNothing() {
        MemStore store = new MemStore();
        List<SpilledRow> file = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            store.add("E" + i, hr("N" + i), EmployeeStatus.ACTIVE, BEFORE);
            file.add(row(i, "E" + i, hr("N" + i)));
        }
        try (BucketSpill spill = new BucketSpill(tmp.resolve("same"), H)) {
            file.forEach(spill::add);
            spill.finishWriting();
            assertThat(differ.differingBuckets(spill.digests(), store.activeDigests())).isEmpty();
        }
    }

    @Test
    void swappedDataBetweenTwoEmployeesIsDetected() {
        MemStore store = new MemStore();
        store.add("A", hr("Alpha"), EmployeeStatus.ACTIVE, BEFORE);
        store.add("B", hr("Beta"), EmployeeStatus.ACTIVE, BEFORE);
        Recorder r = run(store, List.of(row(0, "A", hr("Beta")), row(1, "B", hr("Alpha"))));
        assertThat(r.changed).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void rejectedRowWithReadableIdCountsAsPresent() {
        MemStore store = new MemStore();
        store.add("E1", hr("One"), EmployeeStatus.ACTIVE, BEFORE);
        Recorder r = run(store, List.of(SpilledRow.rejected(0, "E1")));
        assertThat(r.missing).isEmpty();
        assertThat(r.changed).isEmpty();
    }

    @Test
    void duplicateRowsAreReportedNotCancelled() {
        MemStore store = new MemStore();
        store.add("E1", hr("One"), EmployeeStatus.ACTIVE, BEFORE);
        Recorder r = run(store, List.of(row(0, "E1", hr("One")), row(1, "E1", hr("One"))));
        assertThat(r.dupes).containsExactly("E1");
        assertThat(r.changed).isEmpty();
    }

    @Test
    void employeeChangedAfterExportCutIsNotMissing() {
        MemStore store = new MemStore();
        store.add("OLD", hr("Old"), EmployeeStatus.ACTIVE, BEFORE);
        store.add("JOINED_LATE", hr("Late"), EmployeeStatus.ACTIVE, AS_OF.plusSeconds(60));
        Recorder r = run(store, List.of());
        assertThat(r.missing).containsExactly("OLD");
    }

    @Test
    void inactiveEmployeeInFileIsAChangeAndInactiveAbsentIsNotMissing() {
        MemStore store = new MemStore();
        store.add("BACK", hr("Back"), EmployeeStatus.INACTIVE, BEFORE);
        store.add("GONE", hr("Gone"), EmployeeStatus.INACTIVE, BEFORE);
        Recorder r = run(store, List.of(row(0, "BACK", hr("Back"))));
        assertThat(r.changed).containsExactly("BACK");
        assertThat(r.missing).isEmpty();
    }
}
