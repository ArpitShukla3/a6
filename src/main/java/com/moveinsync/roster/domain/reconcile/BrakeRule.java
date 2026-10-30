package com.moveinsync.roster.domain.reconcile;

import java.util.Optional;

/** One reason to stop a reconciliation run. Returns a reason code if tripped. */
public interface BrakeRule {
    Optional<String> evaluate(BrakeInput in);
}
