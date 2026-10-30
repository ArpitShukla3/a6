package com.moveinsync.roster.domain.validation;

import java.util.List;

/** Office must exist for the tenant. */
public final class OfficeCodeValidator implements RecordValidator {
    @Override
    public void validate(IncomingRecord r, ValidationContext ctx, List<FieldError> errors) {
        if (r.type() == null || !r.type().carriesHrFields() || r.officeCode() == null || r.officeCode().isBlank()) {
            return;
        }
        if (!ctx.officeCodes().contains(r.officeCode().trim())) {
            errors.add(new FieldError("officeCode", ErrorCodes.OFFICE_UNKNOWN));
        }
    }
}
