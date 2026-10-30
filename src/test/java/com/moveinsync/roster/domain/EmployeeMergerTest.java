package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.event.UpdateEmployeeEvent;
import com.moveinsync.roster.domain.policy.ChangeKind;
import com.moveinsync.roster.domain.policy.EmployeeMerger;
import com.moveinsync.roster.domain.policy.FieldOwnershipPolicy;
import com.moveinsync.roster.domain.policy.VersionGate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.moveinsync.roster.domain.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class EmployeeMergerTest {

    private final EmployeeMerger merger = new EmployeeMerger(HASHER);
    private final VersionGate gate = new VersionGate(HASHER);

    @Test
    void hrUpdateNeverTouchesTransportFields() {
        var current = active("E1", 1, hr("BLR-01", "S1"));
        var ev = new UpdateEmployeeEvent("E1", 2, T0, hr("BLR-01", "S2"));
        var m = merger.merge(TENANT, current, ev, gate.decide(current, ev).transition());
        assertThat(m.state().pickupPointId()).isEqualTo("PP-1");
        assertThat(m.state().specialAssistance()).isEqualTo("WHEELCHAIR");
        assertThat(m.state().adminVersion()).isEqualTo(3);
        assertThat(m.changedFields()).containsExactly("shiftCode");
        assertThat(m.kind()).isEqualTo(ChangeKind.UPDATED);
    }

    @Test
    void officeTransferFlagsPickupForReviewButKeepsIt() {
        var current = active("E1", 1, hr("BLR-01", "S1"));
        var ev = new UpdateEmployeeEvent("E1", 2, T0, hr("HYD-01", "S1"));
        var m = merger.merge(TENANT, current, ev, gate.decide(current, ev).transition());
        assertThat(m.state().pickupPointId()).isEqualTo("PP-1");
        assertThat(m.state().pickupNeedsReview()).isTrue();
        assertThat(m.changedFields()).contains("officeCode", "pickupNeedsReview");
    }

    @Test
    void transportEditDoesNotTouchHrFieldsOrVersion() {
        var current = active("E1", 7, hr("BLR-01", "S1"));
        var next = current.withTransport("PP-9", null);
        assertThat(next.hr()).isEqualTo(current.hr());
        assertThat(next.hrVersion()).isEqualTo(7);
        assertThat(next.adminVersion()).isEqualTo(4);
    }

    @Test
    void ownershipPolicyFlagsTransportFieldsInHrPayload() {
        assertThat(new FieldOwnershipPolicy().violationsInHrPayload(Set.of("pickupPointId", "name")))
                .containsExactly("pickupPointId");
    }

    @Test
    void toStringNeverPrintsPersonalData() {
        var s = active("E1", 1, hr("BLR-01", "S1"));
        assertThat(s.toString() + s.hr().toString()).doesNotContain("Asha", "asha@", "9876543210");
    }
}
