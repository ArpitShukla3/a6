package com.moveinsync.roster.domain.validation;

import java.util.List;
import java.util.regex.Pattern;

/** Formats of identifiers, version, name, email and shift code. */
public final class FormatValidator implements RecordValidator {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}$");
    private static final Pattern SHIFT = Pattern.compile("[A-Z0-9_-]{1,16}");

    @Override
    public void validate(IncomingRecord r, ValidationContext ctx, List<FieldError> errors) {
        if (r.externalId() != null && !r.externalId().isBlank() && !ID.matcher(r.externalId()).matches()) {
            errors.add(new FieldError("externalId", ErrorCodes.EXTERNAL_ID_INVALID));
        }
        if (r.version() != null && r.version() <= 0) {
            errors.add(new FieldError("sourceVersion", ErrorCodes.VERSION_INVALID));
        }
        if (r.type() == null || !r.type().carriesHrFields()) {
            return;
        }
        if (r.name() != null && !r.name().isBlank() && (r.name().length() > 200 || r.name().chars().anyMatch(Character::isISOControl))) {
            errors.add(new FieldError("name", ErrorCodes.NAME_INVALID));
        }
        if (r.email() != null && !r.email().isBlank() && !EMAIL.matcher(r.email().trim()).matches()) {
            errors.add(new FieldError("email", ErrorCodes.EMAIL_INVALID));
        }
        if (r.shiftCode() != null && !r.shiftCode().isBlank() && !SHIFT.matcher(r.shiftCode()).matches()) {
            errors.add(new FieldError("shiftCode", ErrorCodes.SHIFT_CODE_INVALID));
        }
        String mgr = r.managerExternalId();
        if (mgr != null && !mgr.isBlank() && (!ID.matcher(mgr).matches() || mgr.equals(r.externalId()))) {
            // Format only: the manager may legitimately arrive later in the feed.
            errors.add(new FieldError("managerExternalId", ErrorCodes.MANAGER_INVALID));
        }
    }
}
