package com.moveinsync.roster.domain.reconcile;

import com.moveinsync.roster.domain.model.HrProfile;

/**
 * A row of the export after validation. A rejected row whose employee ID could be read is kept
 * (with {@code rejected=true}) so that employee counts as present, not missing.
 */
public record SpilledRow(int index, String externalId, long version, long digest, boolean rejected, HrProfile hr) {

    public static SpilledRow rejected(int index, String externalId) {
        return new SpilledRow(index, externalId, 0, 0, true, null);
    }

    @Override
    public String toString() {
        return "SpilledRow[#" + index + ", ext=" + externalId + ", v=" + version + ", rejected=" + rejected + "]";
    }
}
