package com.moveinsync.roster.domain.reconcile;

import java.util.Optional;

/** Trips when the share of active employees missing from the file reaches the threshold (>=). */
public final class MissingRatioRule implements BrakeRule {
    @Override
    public Optional<String> evaluate(BrakeInput in) {
        return in.activeBefore() > 0 && in.missingRatio() >= in.missingThreshold()
                ? Optional.of("MISSING_RATIO") : Optional.empty();
    }
}
