package com.moveinsync.roster.domain.reconcile;

/** What a full read of the file found. */
public record SnapshotScan(SnapshotHeader header, int rowsRead, Integer trailerRows) {

    /** Missing trailer, or row count disagreeing with the trailer or the header. */
    public boolean truncated() {
        if (trailerRows == null || trailerRows != rowsRead) {
            return true;
        }
        return header.expectedRows() != null && header.expectedRows() != rowsRead;
    }
}
