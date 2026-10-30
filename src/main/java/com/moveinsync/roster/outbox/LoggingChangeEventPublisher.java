package com.moveinsync.roster.outbox;

import com.moveinsync.roster.repository.OutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** For running without a broker. Logs IDs and type only. */
@Component
@ConditionalOnProperty(name = "roster.outbox.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingChangeEventPublisher implements ChangeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingChangeEventPublisher.class);

    @Override
    public void publish(OutboxRow e) {
        log.info("change event published eventId={} type={} employeeId={}", e.eventId(), e.eventType(), e.aggregateId());
    }
}
