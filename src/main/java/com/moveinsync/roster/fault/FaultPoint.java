package com.moveinsync.roster.fault;

/** Places where tests can inject a crash. */
public enum FaultPoint {
    /** Inside the ingest transaction, after the employee row is written, before the outbox row. */
    INGEST_AFTER_EMPLOYEE_WRITE,
    /** Relay picked up events but has not published yet ("crash between DB write and publish"). */
    RELAY_BEFORE_PUBLISH,
    /** Relay published but crashed before recording that it did. */
    RELAY_AFTER_PUBLISH_BEFORE_MARK,
    /** Reconciliation apply pass, before each change. */
    RECON_APPLY_STEP
}
