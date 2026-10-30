package com.moveinsync.roster.domain.policy;

/** Result of applying one event. */
public enum Outcome {
    /** State changed; an outbox event was written. */
    APPLIED,
    /** Version older than stored. Ignored. */
    STALE,
    /** Same version and same content as stored. No-op. */
    DUPLICATE,
    /** Same version as stored but different content: a source-system bug. Rejected, not guessed. */
    CONFLICT,
    /** Failed validation at the boundary. */
    REJECTED,
    /** Infrastructure error (e.g. database down). Safe to retry. */
    FAILED
}
