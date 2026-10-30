package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.domain.validation.FieldError;

import java.util.List;

/** Per-record result. Errors and warnings carry field names and codes only. */
public record RecordResult(int index, String externalId, Outcome outcome,
                           List<FieldError> errors, List<FieldError> warnings) {
}
