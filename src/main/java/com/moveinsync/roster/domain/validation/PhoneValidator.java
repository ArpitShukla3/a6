package com.moveinsync.roster.domain.validation;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.List;

/** Phone is optional; when present it must be a valid number. Numbers without +CC default to India. */
public final class PhoneValidator implements RecordValidator {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();
    private static final String DEFAULT_REGION = "IN";

    @Override
    public void validate(IncomingRecord r, ValidationContext ctx, List<FieldError> errors) {
        if (r.type() == null || !r.type().carriesHrFields() || r.phone() == null || r.phone().isBlank()) {
            return;
        }
        if (toE164(r.phone()) == null) {
            errors.add(new FieldError("phone", ErrorCodes.PHONE_INVALID));
        }
    }

    /** Normalised E.164 form, or null if invalid. */
    public static String toE164(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 32) {
            return null;
        }
        try {
            Phonenumber.PhoneNumber n = UTIL.parse(raw.trim(), DEFAULT_REGION);
            return UTIL.isValidNumber(n) ? UTIL.format(n, PhoneNumberUtil.PhoneNumberFormat.E164) : null;
        } catch (NumberParseException e) {
            return null;
        }
    }
}
