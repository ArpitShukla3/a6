package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.policy.Outcome;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BatchResult(UUID batchId, Map<Outcome, Integer> summary, List<RecordResult> results) {

    public static BatchResult of(UUID batchId, List<RecordResult> results) {
        Map<Outcome, Integer> summary = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) {
            summary.put(o, 0);
        }
        results.forEach(r -> summary.merge(r.outcome(), 1, Integer::sum));
        return new BatchResult(batchId, summary, results);
    }
}
