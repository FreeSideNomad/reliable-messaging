# Reliable Messaging E2E Test Plan

## Objectives
- Exercise the full stack (Micronaut app + Docker Compose infrastructure) through the real REST entry point.
- Assert cross-system side effects in PostgreSQL, IBM MQ reply queue, and Kafka topic for both happy-path and failure outcomes.
- Keep execution deterministic and re-runnable without manual cleanup.
- Stay portable: plain JUnit 5 + JDK/JMS/Kafka clients only (no heavyweight E2E frameworks), compatible with enterprise CI/CD and Jacoco coverage.

## Target Scenarios
- **Happy path** – `CreateUser` succeeds, command marked `SUCCEEDED`, reply message `CommandCompleted`, Kafka event emitted with aggregate snapshot.
- **Permanent failure** – payload containing `"failPermanent"` causes command to park in DLQ, reply message `CommandFailed`, Kafka failure event emitted, command table reflects `FAILED`.
- (Optional extension) Transient failure with automatic retry, keeping plan extensible.

## High-Level Orchestration
1. **Docker Compose lifecycle**
   - `docker-compose up -d` before tests (reuse existing `start.sh` or invoke directly).
   - Wait for healthy Postgres/Kafka/MQ using simple polling against sockets/health checks.
   - `docker-compose down` only when explicitly requested; keep services running for local reruns.
2. **Application process**
   - Build once (`mvn -DskipTests package`) before E2E phase.
   - Launch the fat jar (`java -jar target/reliable-single-1.0.0.jar`) via `ProcessBuilder` inside the test harness with inherited env (`.env` vars) and redirect logs to `target/e2e-app.log`.
   - Poll `http://localhost:8080/health` (or `/commands` 404 check) until ready; cap wait to 60s.
   - Ensure graceful shutdown after the suite (`process.destroy()` + timeout fallback).

## Test Harness Design
- **Location**: `src/test/java/com/acme/reliable/e2e/`.
- **Test runner**: JUnit 5 with `@Tag("e2e")`. Core class `ReliableMessagingE2ETest`.
- **Shared utilities**:
  - `EnvironmentSupport` – helpers to load `.env`, spawn/stop app process, wait for ports.
  - `PostgresClient` – uses JDBC (same driver as production) for queries and cleanup.
  - `MqClient` – thin JMS helper using existing IBM MQ dependencies to send/receive and drain queues.
  - `KafkaClient` – uses Apache Kafka client to consume with random group id scoped per test.
  - `HttpClientSupport` – JDK `HttpClient` wrapper for REST calls + retry logic.
- **Rerunnable data**:
  - Generate a unique UUID per test for `Idempotency-Key` and business key.
  - Prior to assertions, drain `APP.CMD.REPLY.Q` for matching correlation id only (non-destructive to other messages).
  - Kafka consumer starts at latest offset but rewinds on matching key.
  - Database asserts on unique identifiers; no truncation required.
- **Timing**:
  - Custom polling loops (sleep/retry) for asynchronous propagation with sensible timeouts (<= 30s).
  - Avoid external dependencies like Awaitility to keep footprint small.

## Scenario Flows
### Happy Path
1. Issue POST `/commands/CreateUser` with unique payload and headers.
2. Capture response headers (`X-Command-Id`, `X-Correlation-Id`).
3. Poll PostgreSQL `command` table by `id` until `status = 'SUCCEEDED'` (with `retries <= timeout`).
4. Pull IBM MQ reply by correlation id; assert `CommandCompleted` and payload contains `userId`.
5. Consume Kafka topic `events.CreateUser` for matching key; assert event type `CommandCompleted`, payload matches snapshot structure.
6. Optionally validate `outbox` row marked `PUBLISHED`.

### Permanent Failure Path
1. POST payload including `"failPermanent": true`.
2. Expect HTTP 500 with `X-Command-Id` header (or 200 with error body depending on app behavior); capture command id.
3. Poll PostgreSQL for `status = 'FAILED'` and `last_error` containing `PermanentException`.
4. Verify entry in `command_dlq` for same command id with reason `Permanent`.
5. Consume IBM MQ reply message; assert `CommandFailed`, payload error detail.
6. Consume Kafka event; assert type `CommandFailed` and error JSON.

## CI/CD Integration
- Add Maven profile `e2e` that:
  - Executes `mvn test -Pe2e` locally and in pipelines.
  - Configures Surefire to include `@Tag("e2e")` tests only when profile active (default build skips them).
  - Reuses existing Jacoco `prepare-agent` so E2E tests contribute coverage; ensure `jacoco:report` runs after profile execution.
- Provide helper script `./status.sh e2e` (or similar) to start/stop/verify infra for developers.

## Open Questions & Follow-Ups
- Confirm desired teardown policy for Docker services in CI (stop after suite vs keep running for debugging).
- Decide whether to cover transient retry scenario now or later.
- Validate IBM MQ queue creation expectations in non-local environments (plan assumes queues already exist).

