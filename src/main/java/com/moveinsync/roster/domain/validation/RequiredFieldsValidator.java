package com.moveinsync.roster.domain.validation;

import java.util.List;

public final class RequiredFieldsValidator implements RecordValidator {
    @Override
    public void validate(IncomingRecord r, ValidationContext ctx, List<FieldError> errors) {
        if (r.type() == null) {
            errors.add(new FieldError("type", ErrorCodes.TYPE_INVALID));
            return;
        }
        if (blank(r.externalId())) errors.add(new FieldError("externalId", ErrorCodes.REQUIRED));
        if (r.version() == null) errors.add(new FieldError("sourceVersion", ErrorCodes.REQUIRED));
        if (r.occurredAt() == null && r.type() != RecordType.SNAPSHOT) {
            errors.add(new FieldError("occurredAt", ErrorCodes.REQUIRED));
        }
        if (r.type().carriesHrFields()) {
            if (blank(r.name())) errors.add(new FieldError("name", ErrorCodes.REQUIRED));
            if (blank(r.email())) errors.add(new FieldError("email", ErrorCodes.REQUIRED));
            if (blank(r.officeCode())) errors.add(new FieldError("officeCode", ErrorCodes.REQUIRED));
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
