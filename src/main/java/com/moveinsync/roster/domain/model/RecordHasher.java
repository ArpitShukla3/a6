package com.moveinsync.roster.domain.model;

import java.nio.charset.StandardCharsets;

/**
 * 64-bit hashing shared by the real-time path (stored in employee.hr_digest / employee.bucket)
 * and the nightly diff, so both sides produce identical values. FNV-1a followed by the
 * MurmurHash3 finaliser for good bit mixing (important because bucket digests are summed).
 */
public final class RecordHasher {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final int bucketCount;

    public RecordHasher(int bucketCount) {
        if (bucketCount <= 0 || bucketCount > Short.MAX_VALUE) {
            throw new IllegalArgumentException("bucketCount out of range");
        }
        this.bucketCount = bucketCount;
    }

    public int bucketCount() {
        return bucketCount;
    }

    /** Digest of identity + HR content. Two employees with swapped data hash differently. */
    public long digest(String externalId, HrProfile hr) {
        return hash(externalId + "\u001E" + hr.canonical());
    }

    public int bucket(String externalId) {
        return Math.floorMod(hash(externalId), bucketCount);
    }

    public static long hash(String s) {
        long h = FNV_OFFSET;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return fmix64(h);
    }

    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
