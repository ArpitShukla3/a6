package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.policy.ChangeKind;
import com.moveinsync.roster.domain.policy.Outcome;

import java.util.UUID;

public record ApplyResult(Outcome outcome, UUID employeeId, ChangeKind kind) {
}
