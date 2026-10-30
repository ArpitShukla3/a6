package com.moveinsync.roster.domain.reconcile;

/**
 * Order-independent summary per bucket: wrapping sum of row digests plus a row count. A sum
 * (unlike XOR) does not cancel out duplicate rows, and the count catches them too.
 */
public final class BucketDigests {

    private final long[] sum;
    private final int[] count;

    public BucketDigests(int buckets) {
        this.sum = new long[buckets];
        this.count = new int[buckets];
    }

    public void add(int bucket, long digest) {
        sum[bucket] += digest;
        count[bucket]++;
    }

    public int buckets() {
        return sum.length;
    }

    public int total() {
        int t = 0;
        for (int c : count) t += c;
        return t;
    }

    public boolean sameAs(BucketDigests other, int bucket) {
        return sum[bucket] == other.sum[bucket] && count[bucket] == other.count[bucket];
    }
}
