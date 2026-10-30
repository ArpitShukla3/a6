package com.moveinsync.roster.domain.policy;

import com.moveinsync.roster.domain.event.RosterEvent;
import com.moveinsync.roster.domain.event.Transition;
import com.moveinsync.roster.domain.model.DeactivatedBy;
import com.moveinsync.roster.domain.model.EmployeeState;
import com.moveinsync.roster.domain.model.RecordHasher;

import java.util.Objects;

/**
 * Per-employee ordering and idempotency.
 *
 * <p>Idempotency key: (tenant, externalId, sourceVersion). Rules, with v = incoming version and
 * s = stored hr_version:
 * <ul>
 *   <li>unknown employee &rarr; APPLIED</li>
 *   <li>v &lt; s &rarr; STALE</li>
 *   <li>v == s and stored was deactivated by reconciliation and the event revives &rarr; APPLIED</li>
 *   <li>v == s and the event would produce exactly the stored state &rarr; DUPLICATE</li>
 *   <li>v == s otherwise &rarr; CONFLICT</li>
 *   <li>v &gt; s &rarr; APPLIED</li>
 * </ul>
 * The caller must hold a row lock on the employee while deciding and writing.
 */
public final class VersionGate {

    private final RecordHasher hasher;

    public VersionGate(RecordHasher hasher) {
        this.hasher = hasher;
    }

    public Decision decide(EmployeeState current, RosterEvent event) {
        Transition target = event.transition(current);
        if (current == null) {
            return new Decision(Outcome.APPLIED, target);
        }
        long v = event.version();
        long s = current.hrVersion();
        if (v < s) {
            return new Decision(Outcome.STALE, target);
        }
        if (v > s) {
            return new Decision(Outcome.APPLIED, target);
        }
        if (current.deactivatedBy() == DeactivatedBy.RECONCILIATION && event.revivesReconciliationDeactivation()) {
            return new Decision(Outcome.APPLIED, target);
        }
        boolean sameContent = hasher.digest(event.externalId(), target.hr()) == current.hrDigest()
                && target.status() == current.status()
                && Objects.equals(target.deactivatedBy(), current.deactivatedBy());
        return new Decision(sameContent ? Outcome.DUPLICATE : Outcome.CONFLICT, target);
    }
}
