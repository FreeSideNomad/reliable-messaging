package com.acme.reliable.kafka;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(beans = KafkaProducer.class)
public class MnKafkaPublisher implements com.acme.reliable.spi.EventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(MnKafkaPublisher.class);
    private final KafkaProducer<String, String> producer;

    public MnKafkaPublisher(KafkaProducer<String, String> p) {
        this.producer = p;
    }

    @Override
    public void publish(String topic, String key, String value, java.util.Map<String,String> headers) {
        var rec = new ProducerRecord<String, String>(topic, key, value);
        if (headers != null) {
            headers.forEach((k, v) -> rec.headers().add(k, v.getBytes()));
        }

        // Send with callback for proper error handling
        // This prevents silent failures and allows proper error propagation
        producer.send(rec, (metadata, exception) -> {
            if (exception != null) {
                LOG.error("Failed to publish message to topic {}, key {}: {}",
                    topic, key, exception.getMessage(), exception);
                // Exception will be caught by OutboxRelay for retry logic
                throw new RuntimeException("Failed to publish to Kafka topic: " + topic, exception);
            } else {
                LOG.debug("Successfully published message to topic {} partition {} offset {}",
                    metadata.topic(), metadata.partition(), metadata.offset());
            }
        });
    }
}
