package com.moveinsync.roster.domain.validation;

import com.moveinsync.roster.domain.model.HrProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runs every validator, collecting all errors for a record, then normalises valid HR fields. */
public final class RecordValidationChain {

    private final List<RecordValidator> validators;

    public RecordValidationChain(List<RecordValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    public static RecordValidationChain standard() {
        return new RecordValidationChain(List.of(
                new RequiredFieldsValidator(),
                new FormatValidator(),
                new PhoneValidator(),
                new OfficeCodeValidator()));
    }

    public List<FieldError> validate(IncomingRecord r, ValidationContext ctx) {
        List<FieldError> errors = new ArrayList<>();
        for (RecordValidator v : validators) {
            v.validate(r, ctx, errors);
        }
        return errors;
    }

    /** Call only for a record that passed {@link #validate}. */
    public HrProfile normalize(IncomingRecord r) {
        return new HrProfile(
                r.name().trim(),
                r.email().trim().toLowerCase(Locale.ROOT),
                r.phone() == null || r.phone().isBlank() ? null : PhoneValidator.toE164(r.phone()),
                blankToNull(r.managerExternalId()),
                r.officeCode().trim(),
                blankToNull(r.shiftCode()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
