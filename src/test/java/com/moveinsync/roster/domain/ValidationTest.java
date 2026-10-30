package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.validation.FieldError;
import com.moveinsync.roster.domain.validation.IncomingRecord;
import com.moveinsync.roster.domain.validation.RecordType;
import com.moveinsync.roster.domain.validation.RecordValidationChain;
import com.moveinsync.roster.domain.validation.ValidationContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.moveinsync.roster.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationTest {

    private final RecordValidationChain chain = RecordValidationChain.standard();
    private final ValidationContext ctx = new ValidationContext(Set.of("BLR-01"));

    private IncomingRecord rec(String phone, String office, String email) {
        return new IncomingRecord(RecordType.UPDATE, "E1", 3L, T0, "Asha", email, phone, "M1", office, "S1", Set.of());
    }

    @Test
    void validRecordPassesAndIsNormalised() {
        var r = rec("98765 43210", "BLR-01", "Asha@Acme.TEST");
        assertThat(chain.validate(r, ctx)).isEmpty();
        var hr = chain.normalize(r);
        assertThat(hr.phone()).isEqualTo("+919876543210");
        assertThat(hr.email()).isEqualTo("asha@acme.test");
    }

    @Test
    void malformedPhoneAndUnknownOfficeAreReportedTogether() {
        assertThat(chain.validate(rec("12ab", "XXX-99", "a@b.co"), ctx))
                .containsExactlyInAnyOrder(new FieldError("phone", "PHONE_INVALID"), new FieldError("officeCode", "OFFICE_UNKNOWN"));
    }

    @Test
    void missingFieldsAreRequired() {
        var r = new IncomingRecord(RecordType.CREATE, null, null, null, null, null, null, null, null, null, Set.of());
        assertThat(chain.validate(r, ctx)).extracting(FieldError::field)
                .contains("externalId", "sourceVersion", "occurredAt", "name", "email", "officeCode");
    }

    @Test
    void deactivateNeedsNoHrFields() {
        var r = new IncomingRecord(RecordType.DEACTIVATE, "E1", 9L, T0, null, null, null, null, null, null, Set.of());
        assertThat(chain.validate(r, ctx)).isEmpty();
    }

    @Test
    void errorsNeverContainTheRejectedValue() {
        var errors = chain.validate(rec("+1-SECRET-PHONE", "BLR-01", "not-an-email"), ctx);
        assertThat(errors.toString()).doesNotContain("SECRET", "not-an-email");
    }

    @Test
    void selfManagerAndBadVersionAreRejected() {
        var r = new IncomingRecord(RecordType.UPDATE, "E1", 0L, T0, "A", "a@b.co", null, "E1", "BLR-01", null, Set.of());
        assertThat(chain.validate(r, ctx)).extracting(FieldError::code).contains("VERSION_INVALID", "MANAGER_INVALID");
    }
}
