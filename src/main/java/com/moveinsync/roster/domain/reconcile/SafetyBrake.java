package com.moveinsync.roster.domain.reconcile;

import java.util.List;
import java.util.Optional;

/** Evaluates all rules; any one tripping holds the run for an operator. */
public final class SafetyBrake {

    private final List<BrakeRule> rules;

    public SafetyBrake(List<BrakeRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static SafetyBrake standard() {
        return new SafetyBrake(List.of(new MissingRatioRule(), new TruncatedFileRule(), new RejectedRatioRule()));
    }

    public BrakeDecision evaluate(BrakeInput in) {
        List<String> reasons = rules.stream().map(r -> r.evaluate(in)).flatMap(Optional::stream).toList();
        return new BrakeDecision(!reasons.isEmpty(), reasons, in.missingRatio());
    }
}
