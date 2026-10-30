package com.moveinsync.roster.domain;

import com.moveinsync.roster.domain.event.CreateEmployeeEvent;
import com.moveinsync.roster.domain.event.DeactivateEmployeeEvent;
import com.moveinsync.roster.domain.event.SnapshotRowEvent;
import com.moveinsync.roster.domain.event.UpdateEmployeeEvent;
import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeStatus;
import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.domain.policy.VersionGate;
import org.junit.jupiter.api.Test;

import static com.moveinsync.roster.domain.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class VersionGateTest {

    private final VersionGate gate = new VersionGate(HASHER);

    @Test
    void unknownEmployeeIsApplied() {
        assertThat(gate.decide(null, new CreateEmployeeEvent("E1", 1, T0, hr("BLR-01", "S1"))).outcome())
                .isEqualTo(Outcome.APPLIED);
    }

    @Test
    void staleUpdateIsIgnored() {
        var current = active("E1", 7, hr("BLR-02", "S2"));
        var v6 = new UpdateEmployeeEvent("E1", 6, T0, hr("BLR-01", "S1"));
        assertThat(gate.decide(current, v6).outcome()).isEqualTo(Outcome.STALE);
    }

    @Test
    void newerUpdateIsApplied() {
        var current = active("E1", 6, hr("BLR-01", "S1"));
        assertThat(gate.decide(current, new UpdateEmployeeEvent("E1", 7, T0, hr("BLR-02", "S2"))).outcome())
                .isEqualTo(Outcome.APPLIED);
    }

    @Test
    void sameVersionSameContentIsDuplicate() {
        var h = hr("BLR-01", "S1");
        var current = active("E1", 5, h);
        assertThat(gate.decide(current, new UpdateEmployeeEvent("E1", 5, T0, h)).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(gate.decide(current, new CreateEmployeeEvent("E1", 5, T0, h)).outcome()).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void sameVersionDifferentContentIsConflict() {
        var current = active("E1", 5, hr("BLR-01", "S1"));
        assertThat(gate.decide(current, new UpdateEmployeeEvent("E1", 5, T0, hr("BLR-01", "S9"))).outcome())
                .isEqualTo(Outcome.CONFLICT);
    }

    @Test
    void duplicateDeactivateIsNoOp() {
        var current = state("E1", 9, hr("BLR-01", "S1"), EmployeeStatus.INACTIVE, DeactivatedBy.HR);
        assertThat(gate.decide(current, new DeactivateEmployeeEvent("E1", 9, T0)).outcome()).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void lateCreateAfterTombstoneIsStale() {
        var tombstone = state("E1", 4, hr("BLR-01", "S1"), EmployeeStatus.INACTIVE, DeactivatedBy.HR);
        assertThat(gate.decide(tombstone, new CreateEmployeeEvent("E1", 3, T0, hr("BLR-01", "S1"))).outcome())
                .isEqualTo(Outcome.STALE);
    }

    @Test
    void updateDoesNotReactivateHrDeactivatedEmployee() {
        var current = state("E1", 4, hr("BLR-01", "S1"), EmployeeStatus.INACTIVE, DeactivatedBy.HR);
        var d = gate.decide(current, new UpdateEmployeeEvent("E1", 5, T0, hr("BLR-02", "S1")));
        assertThat(d.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(d.transition().status()).isEqualTo(EmployeeStatus.INACTIVE);
    }

    @Test
    void newerCreateRehires() {
        var current = state("E1", 4, hr("BLR-01", "S1"), EmployeeStatus.INACTIVE, DeactivatedBy.HR);
        var d = gate.decide(current, new CreateEmployeeEvent("E1", 5, T0, hr("BLR-01", "S1")));
        assertThat(d.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(d.transition().status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void sameVersionRecordRevivesReconciliationDeactivation() {
        var h = hr("BLR-01", "S1");
        var current = state("E1", 4, h, EmployeeStatus.INACTIVE, DeactivatedBy.RECONCILIATION);
        var d = gate.decide(current, new SnapshotRowEvent("E1", 4, T0, h));
        assertThat(d.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(d.transition().status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(gate.decide(current, new UpdateEmployeeEvent("E1", 4, T0, h)).transition().status())
                .isEqualTo(EmployeeStatus.ACTIVE);
        // ...but an older record does not.
        assertThat(gate.decide(current, new SnapshotRowEvent("E1", 3, T0, h)).outcome()).isEqualTo(Outcome.STALE);
    }
}
