package com.moveinsync.roster.domain.reconcile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-level diff of the export against the store.
 * <ol>
 *   <li>Compare per-bucket digests (file vs store). Equal buckets are skipped entirely.</li>
 *   <li>For each differing bucket, load that bucket's rows from both sides (about N/B rows each)
 *       and compare them row by row.</li>
 * </ol>
 * Time O(N). Heap O(B + N/B): only one bucket is in memory at a time.
 */
public final class BucketedSnapshotDiffer {

    public List<Integer> differingBuckets(BucketDigests file, BucketDigests store) {
        List<Integer> out = new ArrayList<>();
        for (int b = 0; b < file.buckets(); b++) {
            if (!file.sameAs(store, b)) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * @param asOf the export's as-of time. An active employee missing from the file whose last HR
     *             change happened at or after asOf was created/changed after the export was cut, so
     *             is not reported as missing.
     */
    public DiffStats drill(List<Integer> buckets, BucketSpill spill, StoreView store, Instant asOf, DiffSink sink) {
        int news = 0, changed = 0, missing = 0, dupes = 0, newer = 0;
        for (int b : buckets) {
            Map<String, SpilledRow> fileRows = new LinkedHashMap<>();
            for (SpilledRow row : spill.readBucket(b)) {
                if (fileRows.putIfAbsent(row.externalId(), row) != null) {
                    dupes++;
                    sink.onDuplicateRow(row);
                }
            }
            Map<String, StoredRow> stored = new HashMap<>();
            for (StoredRow s : store.loadBucket(b)) {
                stored.put(s.externalId(), s);
            }
            for (SpilledRow row : fileRows.values()) {
                if (row.rejected()) {
                    continue; // present, but nothing to apply
                }
                StoredRow s = stored.get(row.externalId());
                if (s == null) {
                    news++;
                    sink.onNew(row);
                } else if (!s.isActive() || s.hrDigest() != row.digest()) {
                    changed++;
                    sink.onChanged(row, s);
                }
            }
            for (StoredRow s : stored.values()) {
                if (!s.isActive() || fileRows.containsKey(s.externalId())) {
                    continue;
                }
                if (s.hrSourceTs().isBefore(asOf)) {
                    missing++;
                    sink.onMissing(s);
                } else {
                    newer++;
                }
            }
        }
        return new DiffStats(0, buckets.size(), news, changed, missing, dupes, newer);
    }
}
