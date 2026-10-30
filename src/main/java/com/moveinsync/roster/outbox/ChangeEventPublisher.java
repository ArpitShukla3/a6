package com.moveinsync.roster.outbox;

import com.moveinsync.roster.repository.OutboxRow;

/** Sends one change event downstream. Must throw if delivery is not confirmed. */
public interface ChangeEventPublisher {
    void publish(OutboxRow event) throws Exception;
}
