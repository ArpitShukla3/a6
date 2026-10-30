package com.moveinsync.roster.domain.validation;

import java.util.List;

/** One rule checked at the boundary. Implementations add errors; they never throw for bad input. */
public interface RecordValidator {
    void validate(IncomingRecord r, ValidationContext ctx, List<FieldError> errors);
}
