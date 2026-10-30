package com.moveinsync.roster.domain.reconcile;

import java.util.Optional;

/** Trips when the trailer is missing or row counts disagree. */
public final class TruncatedFileRule implements BrakeRule {
    @Override
    public Optional<String> evaluate(BrakeInput in) {
        return in.truncated() ? Optional.of("TRUNCATED_FILE") : Optional.empty();
    }
}
