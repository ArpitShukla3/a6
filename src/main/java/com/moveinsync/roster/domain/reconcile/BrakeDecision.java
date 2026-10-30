package com.moveinsync.roster.domain.reconcile;

import java.util.List;

public record BrakeDecision(boolean tripped, List<String> reasons, double missingRatio) {
}
