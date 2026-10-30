package com.moveinsync.roster.it;

import com.moveinsync.roster.outbox.ChangeEventPublisher;
import com.moveinsync.roster.repository.OutboxRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stands in for the broker. Records every delivery (including redeliveries) and exposes the
 * consumer's deduplicated view by eventId. Can be told to fail.
 */
public class RecordingPublisher implements ChangeEventPublisher {

    public final List<OutboxRow> deliveries = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean failing;

    @Override
    public void publish(OutboxRow event) throws Exception {
        if (failing) {
            throw new java.io.IOException("broker unavailable");
        }
        deliveries.add(event);
    }

    public Set<UUID> uniqueEventIds() {
        synchronized (deliveries) {
            Set<UUID> s = new LinkedHashSet<>();
            deliveries.forEach(d -> s.add(d.eventId()));
            return s;
        }
    }

    public void reset() {
        deliveries.clear();
        failing = false;
    }
}
