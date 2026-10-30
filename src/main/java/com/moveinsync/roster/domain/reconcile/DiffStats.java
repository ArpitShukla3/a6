package com.moveinsync.roster.domain.reconcile;

public record DiffStats(int bucketsCompared,
                        int bucketsDiffering,
                        int newRows,
                        int changedRows,
                        int missing,
                        int duplicateRows,
                        int skippedNewerThanExport) {
}
