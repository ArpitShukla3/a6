package com.moveinsync.roster.it;

import com.moveinsync.roster.domain.policy.Outcome;
import com.moveinsync.roster.fault.FaultPoint;
import com.moveinsync.roster.fault.InjectedFault;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Failure injection around the outbox: the database change and its event never disagree. */
class OutboxFailureIT extends AbstractIT {

    @Test
    void crashBetweenDatabaseWriteAndPublish_eventIsPublishedAfterRestart() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        assertThat(count("SELECT count(*) FROM employee")).isEqualTo(1);

        faults.arm(FaultPoint.RELAY_BEFORE_PUBLISH);           // process "dies" before publishing
        assertThatThrownBy(() -> relay.relayOnce()).isInstanceOf(InjectedFault.class);
        assertThat(publisher.deliveries).isEmpty();
        assertThat(count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")).isEqualTo(1);

        // "restart": the next relay pass (any instance) picks the committed event up
        assertThat(drainOutbox()).isEqualTo(1);
        assertThat(publisher.deliveries).hasSize(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")).isZero();
    }

    @Test
    void crashAfterPublishBeforeMark_isRedeliveredWithSameEventId() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"), create("E2", 1, "BLR-01", "S1"));
        faults.arm(FaultPoint.RELAY_AFTER_PUBLISH_BEFORE_MARK);
        assertThatThrownBy(() -> relay.relayOnce()).isInstanceOf(InjectedFault.class);
        assertThat(publisher.deliveries).hasSize(2);

        drainOutbox();
        assertThat(publisher.deliveries).hasSize(4);             // at-least-once
        assertThat(publisher.uniqueEventIds()).hasSize(2);       // consumer dedupes by eventId
    }

    @Test
    void crashInsideTheIngestTransaction_rollsBackBothChangeAndEvent() {
        faults.arm(FaultPoint.INGEST_AFTER_EMPLOYEE_WRITE);
        var r = ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        assertThat(r.results().get(0).outcome()).isEqualTo(Outcome.FAILED);
        assertThat(count("SELECT count(*) FROM employee")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_event")).isZero();
        // HR retries: applied normally
        assertThat(ingest(ACME, create("E1", 1, "BLR-01", "S1")).results().get(0).outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void brokerOutage_ingestKeepsWorking_eventsWaitInOrderThenDrain() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        publisher.failing = true;
        assertThat(relay.relayOnce()).isZero();
        ingest(ACME, update("E1", 2, "BLR-02", "S1"));             // still accepted
        assertThat(count("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")).isEqualTo(2);
        assertThat(count("SELECT attempts FROM outbox_event ORDER BY id LIMIT 1")).isEqualTo(1);

        publisher.failing = false;
        jdbc.update("UPDATE outbox_event SET next_attempt_at = NULL"); // skip the backoff wait
        drainOutbox();
        assertThat(publisher.deliveries).extracting(d -> d.eventType())
                .containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED");
    }

    @Test
    void backingOffEventBlocksLaterEventsToKeepOrder() {
        ingest(ACME, create("E1", 1, "BLR-01", "S1"));
        publisher.failing = true;
        relay.relayOnce();                                          // first event now backing off
        publisher.failing = false;
        ingest(ACME, update("E1", 2, "BLR-02", "S1"));
        assertThat(relay.relayOnce()).isZero();                     // head-of-line: second must wait
        assertThat(publisher.deliveries).isEmpty();
    }
}
