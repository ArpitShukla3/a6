package com.moveinsync.roster.outbox;

import com.moveinsync.roster.config.RosterProperties;
import com.moveinsync.roster.repository.OutboxRow;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Publishes to Kafka/Redpanda. Key = employee id, so all events of one employee land on one
 * partition in order. Waits for the broker ack (acks=all, idempotent producer).
 */
@Component
@ConditionalOnProperty(name = "roster.outbox.publisher", havingValue = "kafka")
public class KafkaChangeEventPublisher implements ChangeEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final RosterProperties props;

    public KafkaChangeEventPublisher(KafkaTemplate<String, String> kafka, RosterProperties props) {
        this.kafka = kafka;
        this.props = props;
    }

    @Override
    public void publish(OutboxRow e) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(props.outbox().topic(), e.aggregateId().toString(), e.payload());
        record.headers().add("eventId", e.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", e.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("tenantId", e.tenantId().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(props.outbox().publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
