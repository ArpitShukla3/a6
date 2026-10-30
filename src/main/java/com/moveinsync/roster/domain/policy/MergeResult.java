package com.moveinsync.roster.domain.policy;

import com.moveinsync.roster.domain.model.EmployeeState;

import java.util.List;

/** New state plus what changed (field names only, for the change event). */
public record MergeResult(EmployeeState state, ChangeKind kind, List<String> changedFields) {
}
