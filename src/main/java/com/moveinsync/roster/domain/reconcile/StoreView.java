package com.moveinsync.roster.domain.reconcile;

import java.util.List;

/** Read access to the employee store, implemented over SQL in production and in memory in tests. */
public interface StoreView {

    /** Digests of all ACTIVE employees of the tenant, per bucket. Streamed; O(buckets) memory. */
    BucketDigests activeDigests();

    /** All employees of the tenant in one bucket, any status. */
    List<StoredRow> loadBucket(int bucket);
}
