package com.moveinsync.roster.domain.reconcile;

import java.time.Instant;

/** First line of the export: {@code #ROSTER_EXPORT,as_of=<ISO instant>,expected_rows=<n>}. */
public record SnapshotHeader(Instant asOf, Integer expectedRows) {
}
