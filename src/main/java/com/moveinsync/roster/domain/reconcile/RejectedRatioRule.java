package com.moveinsync.roster.domain.reconcile;

import java.util.Optional;

/** Trips when too many rows failed validation: a corrupt file, not a few bad records. */
public final class RejectedRatioRule implements BrakeRule {
    @Override
    public Optional<String> evaluate(BrakeInput in) {
        return in.rowsRead() > 0 && in.rejectedRatio() >= in.rejectedThreshold()
                ? Optional.of("REJECTED_RATIO") : Optional.empty();
    }
}
