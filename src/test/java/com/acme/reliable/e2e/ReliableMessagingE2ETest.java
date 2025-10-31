package com.acme.reliable.e2e;

import com.acme.reliable.e2e.PostgresClient.CommandRow;
import com.acme.reliable.e2e.PostgresClient.DlqEntry;
import com.acme.reliable.e2e.PostgresClient.OutboxRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliableMessagingE2ETest {

    private final ObjectMapper mapper = new ObjectMapper();
    private EnvironmentSupport environment;
    private PostgresClient postgres;
    private HttpClient httpClient;
    private URI baseUri;

    @BeforeAll
    void setUp() {
        this.environment = EnvironmentSupport.load();
        environment.ensureInfrastructureReady();
        this.postgres = new PostgresClient(environment.env());
        this.httpClient = HttpClientSupport.httpClient();
        this.baseUri = HttpClientSupport.baseUri(resolveServerPort(environment.env()));
    }

    @AfterAll
    void tearDown() {
        if (postgres != null) {
            postgres.close();
        }
        environment.shutdownInfrastructure();
    }

    @Test
    @Tag("e2e")
    void createUser_happyPath_publishesEverywhere() throws Exception {
        String payload = "{\"username\":\"e2e-user-" + UUID.randomUUID() + "\"}";
        CommandSubmission submission = submitCommand("CreateUser", payload);

        assertThat(submission.status())
                .as("REST response should be success or accepted")
                .isIn(200, 202);

        CommandRow commandRow = postgres.waitForCommand(
                submission.commandId(),
                row -> "SUCCEEDED".equals(row.status()),
                Duration.ofSeconds(60)
        );
        assertThat(commandRow.retries()).isZero();

        OutboxRow replyRow = postgres.findReplyRow(submission.commandId())
                .orElseThrow(() -> new AssertionError("Reply outbox row not found for " + submission.commandId()));
        assertThat(replyRow.status()).isEqualTo("PUBLISHED");
        JsonNode replyPayload = mapper.readTree(replyRow.payload());
        assertThat(replyPayload.path("userId").asText()).isNotBlank();

        try (KafkaClient kafka = new KafkaClient(environment.env());
             MqClient mq = new MqClient(environment.env())) {
            ConsumerRecord<String, String> record = kafka.pollForRecord(
                    "events.CreateUser",
                    commandRow.businessKey(),
                    Duration.ofSeconds(30)
            ).orElseThrow(() -> new AssertionError("Kafka event not received"));
            assertThat(record.value()).isNotBlank();
            JsonNode kafkaPayload = mapper.readTree(record.value());
            assertThat(kafkaPayload.path("aggregateKey").asText()).isEqualTo(commandRow.businessKey());
            assertThat(mq.browseTextMessage("APP.CMD.CreateUser.Q", submission.commandId().toString(), Duration.ofSeconds(2)))
                    .as("Command queue should be drained for " + submission.commandId())
                    .isEmpty();
        }
    }

    @Test
    @Tag("e2e")
    void createUser_permanentFailure_routesToDlq() throws Exception {
        String payload = "{\"username\":\"e2e-perm-" + UUID.randomUUID() + "\",\"failPermanent\":true}";
        CommandSubmission submission = submitCommand("CreateUser", payload);

        assertThat(submission.status())
                .as("Permanent failure should result in HTTP 200/202/500")
                .isIn(200, 202, 500);

        CommandRow commandRow = postgres.waitForCommand(
                submission.commandId(),
                row -> "FAILED".equals(row.status()),
                Duration.ofSeconds(60)
        );
        assertThat(commandRow.lastError()).contains("Invariant");

        DlqEntry dlqEntry = postgres.fetchDlq(commandRow.id())
                .orElseThrow(() -> new AssertionError("Command did not reach DLQ"));
        assertThat(dlqEntry.reason()).isEqualTo("Permanent");

        OutboxRow replyRow = postgres.findReplyRow(commandRow.id())
                .orElseThrow(() -> new AssertionError("Reply outbox row missing"));
        assertThat(replyRow.status()).isEqualTo("PUBLISHED");
        JsonNode replyPayload = mapper.readTree(replyRow.payload());
        assertThat(replyPayload.path("error").asText()).contains("Invariant");

        try (KafkaClient kafka = new KafkaClient(environment.env());
             MqClient mq = new MqClient(environment.env())) {
            ConsumerRecord<String, String> record = kafka.pollForRecord(
                    "events.CreateUser",
                    commandRow.businessKey(),
                    Duration.ofSeconds(30)
            ).orElseThrow(() -> new AssertionError("Kafka failure event not received"));
            JsonNode kafkaPayload = mapper.readTree(record.value());
            assertThat(kafkaPayload.path("error").asText()).contains("Invariant");
            assertThat(mq.browseTextMessage("APP.CMD.CreateUser.Q", submission.commandId().toString(), Duration.ofSeconds(2)))
                    .as("Command queue should be drained for failed command " + submission.commandId())
                    .isEmpty();
        }
    }

    @Test
    @Tag("e2e")
    void createUser_transientFailure_leavesCommandRunning() throws Exception {
        String payload = "{\"username\":\"e2e-transient-" + UUID.randomUUID() + "\",\"failTransient\":true}";
        CommandSubmission submission = submitCommand("CreateUser", payload);

        assertThat(submission.status()).isEqualTo(202);

        CommandRow commandRow = postgres.waitForCommand(
                submission.commandId(),
                row -> "RUNNING".equals(row.status()) && row.retries() >= 1,
                Duration.ofSeconds(60)
        );
        assertThat(commandRow.lastError()).contains("Downstream timeout");

        try (MqClient mq = new MqClient(environment.env())) {
            assertThat(mq.browseTextMessage("APP.CMD.CreateUser.Q", submission.commandId().toString(), Duration.ofSeconds(5)))
                    .as("Transient failure should keep command message visible")
                    .isPresent();
        }

        assertThat(postgres.findReplyRow(commandRow.id())).isEmpty();
        assertThat(postgres.findEventRow("events.CreateUser", commandRow.businessKey(), "CommandCompleted")).isEmpty();
        assertThat(postgres.findEventRow("events.CreateUser", commandRow.businessKey(), "CommandFailed")).isEmpty();
    }

    private CommandSubmission submitCommand(String name, String payload) throws Exception {
        String idempotencyKey = "e2e-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/commands/" + name))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        UUID commandId = UUID.fromString(header(response, "X-Command-Id"));
        String correlationId = headerOrDefault(response, "X-Correlation-Id", commandId.toString());
        return new CommandSubmission(commandId, correlationId, response.statusCode(), response.body());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name)
                .orElseThrow(() -> new IllegalStateException("Missing header " + name));
    }

    private static String headerOrDefault(HttpResponse<?> response, String name, String fallback) {
        return response.headers().firstValue(name).orElse(fallback);
    }

    private static int resolveServerPort(Map<String, String> env) {
        return Integer.parseInt(env.getOrDefault("MICRONAUT_SERVER_PORT",
                env.getOrDefault("APP_PORT", "8080")));
    }

    private record CommandSubmission(UUID commandId, String correlationId, int status, String body) {
    }
}
