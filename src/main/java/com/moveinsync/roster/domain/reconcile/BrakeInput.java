package com.moveinsync.roster.domain.reconcile;

public record BrakeInput(int activeBefore,
                         int missing,
                         int rowsRead,
                         int rowsRejected,
                         boolean truncated,
                         double missingThreshold,
                         double rejectedThreshold) {

    public double missingRatio() {
        return activeBefore == 0 ? 0.0 : (double) missing / activeBefore;
    }

    public double rejectedRatio() {
        return rowsRead == 0 ? 0.0 : (double) rowsRejected / rowsRead;
    }
}
