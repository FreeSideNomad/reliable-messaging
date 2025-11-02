package com.acme.reliable.kafka;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

@Factory
@Requires(notEnv = "test")
public class KafkaProducerFactory {

    @Singleton
    public KafkaProducer<String, String> kafkaProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // Performance tuning for high throughput (2000+ TPS)
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);  // Small batching delay
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);  // 32KB batch size
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864);  // 64MB buffer
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");  // Fast compression

        // Connection pool settings
        props.put(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 540000);  // 9 minutes
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);  // 30 seconds
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);  // 2 minutes

        props.put(ProducerConfig.CLIENT_ID_CONFIG, "reliable-cmd-producer");

        return new KafkaProducer<>(props);
    }
}
