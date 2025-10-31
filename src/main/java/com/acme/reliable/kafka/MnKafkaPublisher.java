package com.acme.reliable.kafka;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

@Singleton
@Requires(beans = KafkaProducer.class)
public class MnKafkaPublisher implements com.acme.reliable.spi.EventPublisher {
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
        producer.send(rec);
    }
}
