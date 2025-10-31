package com.acme.reliable.kafka;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Singleton
@Requires(notEnv = "test")
public final class KafkaTopicInitializer implements ApplicationEventListener<StartupEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicInitializer.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    private final String bootstrapServers;
    private final List<String> topics;
    private final int partitions;
    private final int replicationFactor;

    public KafkaTopicInitializer(
        @io.micronaut.context.annotation.Value("${kafka.bootstrap.servers}") String configuredBootstrap,
        @io.micronaut.context.annotation.Value("${kafka.required-topics:events.CreateUser}") String requiredTopics,
        @io.micronaut.context.annotation.Value("${kafka.topic-partitions:3}") int partitions,
        @io.micronaut.context.annotation.Value("${kafka.topic-replication-factor:1}") int replicationFactor
    ) {
        String envBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        this.bootstrapServers = normalizeBootstrapServers(
            envBootstrap != null && !envBootstrap.isBlank() ? envBootstrap : configuredBootstrap
        );
        this.topics = parseTopics(requiredTopics);
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        ensureTopics();
    }

    void ensureTopics() {
        if (topics.isEmpty()) {
            LOG.info("No Kafka topics configured for initialization");
            return;
        }

        LOG.info("Ensuring Kafka topics {} using bootstrap servers {}", topics, bootstrapServers);
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (Admin admin = Admin.create(props)) {
            waitForCluster(admin);

            Set<String> existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            List<NewTopic> missing = topics.stream()
                .filter(topic -> !existing.contains(topic))
                .map(topic -> new NewTopic(topic, partitions, (short) replicationFactor))
                .collect(Collectors.toList());

            if (missing.isEmpty()) {
                LOG.info("Kafka topics already exist: {}", topics);
                return;
            }

            admin.createTopics(missing).all().get(10, TimeUnit.SECONDS);
            LOG.info("Created Kafka topics: {}", missing.stream().map(NewTopic::name).collect(Collectors.toList()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while ensuring Kafka topics", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to ensure Kafka topics", e);
        }
    }

    private void waitForCluster(Admin admin) throws InterruptedException, ExecutionException, TimeoutException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                admin.listTopics(new ListTopicsOptions().timeoutMs((int) RETRY_DELAY.toMillis()))
                    .names()
                    .get(RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException | TimeoutException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                LOG.debug("Kafka broker not ready (attempt {}/{}). Retrying...", attempt, MAX_ATTEMPTS);
                TimeUnit.MILLISECONDS.sleep(RETRY_DELAY.toMillis());
            }
        }
    }

    private static List<String> parseTopics(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
            Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .collect(Collectors.toList())
        );
    }

    private static String normalizeBootstrapServers(String value) {
        if (value == null || value.isBlank()) {
            return "localhost:9092";
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(server -> !server.isEmpty())
            .map(server -> server.contains(":") ? server : "localhost:" + server)
            .collect(Collectors.joining(","));
    }
}
