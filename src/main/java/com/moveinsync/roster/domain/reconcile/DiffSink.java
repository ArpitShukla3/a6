package com.moveinsync.roster.domain.reconcile;

/** Receives the differences found in the drill-down pass. */
public interface DiffSink {
    void onNew(SpilledRow row);

    void onChanged(SpilledRow row, StoredRow stored);

    void onMissing(StoredRow stored);

    default void onDuplicateRow(SpilledRow row) {
    }
}
