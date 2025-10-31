# Reliable Commands & Events Framework

A production-ready implementation of a reliable messaging framework using:
- **Micronaut 4.10** - Modern Java framework
- **Java 17** - Language features
- **IBM MQ (JMS)** - Command queue
- **Kafka** - Event streaming
- **PostgreSQL** - Command store and outbox pattern
- **After-commit Fast Path** - Low-latency publishing

## Features

- ✅ Transactional outbox pattern
- ✅ At-least-once delivery guarantees
- ✅ Idempotency protection
- ✅ Fast-path after-commit publishing
- ✅ Background sweep for failed publishes
- ✅ Dead letter queue for permanent failures
- ✅ Command/Event separation
- ✅ 95%+ code and branch coverage requirement

## Architecture

```
┌─────────┐    REST     ┌────────────────┐
│ Client  │────────────▶│ CommandBus     │
└─────────┘             │ (Transactional)│
                        └────────┬───────┘
                                 │ Insert Command (PENDING)
                                 │ Insert Outbox
                                 ▼
                        ┌────────────────┐
                        │   PostgreSQL   │
                        │ Command/Outbox │
                        └────────┬───────┘
                                 │
                  ┌──────────────┴──────────────┐
                  │                             │
          Fast Path (after-commit)      Sweep (scheduled)
                  │                             │
                  ▼                             ▼
          ┌──────────────┐             ┌──────────────┐
          │  OutboxRelay │             │  OutboxRelay │
          └──────┬───────┘             └──────┬───────┘
                 │                            │
                 ├────────────┬───────────────┤
                 ▼            ▼               ▼
            ┌────────┐    ┌──────┐        ┌──────┐
            │ IBM MQ │    │Kafka │        │  MQ  │
            └────┬───┘    └──────┘        └──────┘
                 │
                 ▼
          ┌──────────────┐
          │  Worker      │
          │ (Executor)   │
          └──────────────┘
```

## Project Structure

```
src/main/java/com/acme/reliable/
├── core/          # Domain model and services
│   ├── Envelope.java
│   ├── *Exception.java
│   ├── CommandBus.java
│   ├── Executor.java
│   ├── FastPathPublisher.java
│   ├── Outbox.java
│   └── Jsons.java
├── spi/           # Service provider interfaces
│   ├── CommandStore.java
│   ├── InboxStore.java
│   ├── OutboxStore.java
│   ├── DlqStore.java
│   ├── CommandQueue.java
│   └── EventPublisher.java
├── pg/            # PostgreSQL implementations
│   ├── PgCommandStore.java
│   ├── PgInboxStore.java
│   ├── PgOutboxStore.java
│   └── PgDlqStore.java
├── mq/            # IBM MQ adapter
│   ├── IbmMqFactoryProvider.java
│   ├── JmsCommandQueue.java
│   ├── CommandConsumers.java
│   └── Mappers.java
├── kafka/         # Kafka adapter
│   └── MnKafkaPublisher.java
├── relay/         # Outbox relay
│   └── OutboxRelay.java
├── web/           # REST API
│   └── CommandController.java
└── sample/        # Sample bounded context
    └── CreateUserHandler.java
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for infrastructure)

## Environment Configuration

All passwords and configuration are externalized to environment variables.

### Option 1: Using .env file (Recommended)

1. Copy the example environment file:
```bash
cp .env.example .env
```

2. Edit `.env` and set your passwords:
```bash
# PostgreSQL
POSTGRES_PASSWORD=your-secure-password

# IBM MQ
MQ_PASS=your-mq-password
MQ_APP_PASSWORD=your-mq-app-password
MQ_ADMIN_PASSWORD=your-mq-admin-password
```

3. The `.env` file is automatically loaded by:
   - Docker Compose (for infrastructure)
   - Your IDE (for running the application locally)

### Option 2: Export environment variables

```bash
export POSTGRES_PASSWORD=your-secure-password
export MQ_PASS=your-mq-password
export MQ_APP_PASSWORD=your-mq-app-password
export MQ_ADMIN_PASSWORD=your-mq-admin-password
```

### Available Configuration Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MICRONAUT_SERVER_PORT` | 8080 | Application HTTP port |
| `POSTGRES_HOST` | localhost | PostgreSQL host |
| `POSTGRES_PORT` | 5432 | PostgreSQL port |
| `POSTGRES_DB` | reliable | PostgreSQL database name |
| `POSTGRES_USER` | postgres | PostgreSQL username |
| `POSTGRES_PASSWORD` | postgres | PostgreSQL password |
| `MQ_HOST` | localhost | IBM MQ host |
| `MQ_PORT` | 1414 | IBM MQ port |
| `MQ_QMGR` | QM1 | IBM MQ queue manager name |
| `MQ_CHANNEL` | DEV.APP.SVRCONN | IBM MQ channel |
| `MQ_USER` | app | IBM MQ username |
| `MQ_PASS` | passw0rd | IBM MQ password |
| `MQ_APP_PASSWORD` | passw0rd | IBM MQ app password (docker) |
| `MQ_ADMIN_PASSWORD` | passw0rd | IBM MQ admin password (docker) |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka bootstrap servers |
| `KAFKA_PORT` | 9092 | Kafka port (docker) |
| `JMS_CONSUMERS_ENABLED` | true | Enable/disable JMS consumers |

**Security Note:** Never commit the `.env` file to version control. It's already in `.gitignore`.

## Quick Start

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- PostgreSQL on port 5432
- IBM MQ on ports 1414 (queue) and 9443 (web console)
- Kafka on port 9092

### 2. Build the Project

```bash
mvn clean verify
```

This will:
- Compile all sources
- Run unit and integration tests
- Generate JaCoCo coverage report
- Enforce 95% coverage gate

### 3. Run the Application

```bash
mvn mn:run
```

The application starts on port 8080.

### 4. Submit a Command

```bash
curl -X POST http://localhost:8080/commands/CreateUser \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: unique-key-123" \
  -H "Reply-To: APP.CMD.REPLY.Q" \
  -d '{"username":"alice","email":"alice@example.com"}'
```

Response:
```
HTTP/1.1 202 Accepted
X-Command-Id: <uuid>
X-Correlation-Id: <uuid>
```

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
datasources:
  default:
    url: jdbc:postgresql://localhost:5432/reliable
    username: postgres
    password: postgres

kafka:
  bootstrap:
    servers: localhost:9092
```

Environment variables for IBM MQ:
- `MQ_HOST` - MQ host (default: localhost)
- `MQ_PORT` - MQ port (default: 1414)
- `MQ_QMGR` - Queue manager (default: QM1)
- `MQ_CHANNEL` - Channel (default: DEV.APP.SVRCONN)
- `MQ_USER` - Username (default: app)
- `MQ_PASS` - Password (default: passw0rd)

## Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests (requires containers)
```bash
mvn verify
```

### Coverage Report
After running tests, open `target/site/jacoco/index.html` in a browser.

## Database Schema

The schema is managed by Flyway migrations in `src/main/resources/db/migration/`.

**Tables:**
- `command` - Command store with status tracking
- `inbox` - Idempotency for message processing
- `outbox` - Transactional outbox pattern
- `command_dlq` - Dead letter queue for permanent failures

## Error Handling

### Exception Types

- **TransientException** - Temporary failure, will retry
- **RetryableBusinessException** - Business logic retry
- **PermanentException** - Unrecoverable failure, goes to DLQ

### Example Handler

```java
@Singleton
public class MyHandler implements Executor.HandlerRegistry {
    @Override
    public String invoke(String name, String payload) {
        if (payload.contains("failPermanent")) {
            throw new PermanentException("Invalid data");
        }
        if (payload.contains("failTransient")) {
            throw new TransientException("Service unavailable");
        }
        // Process command
        return "{\"result\":\"success\"}";
    }
}
```

## Sample Commands

### Happy Path
```bash
curl -X POST http://localhost:8080/commands/CreateUser \
  -H "Idempotency-Key: test-1" \
  -d '{"username":"bob"}'
```

### Permanent Failure (DLQ)
```bash
curl -X POST http://localhost:8080/commands/CreateUser \
  -H "Idempotency-Key: test-2" \
  -d '{"failPermanent":true}'
```

### Transient Failure (Retry)
```bash
curl -X POST http://localhost:8080/commands/CreateUser \
  -H "Idempotency-Key: test-3" \
  -d '{"failTransient":true}'
```

## Monitoring

### Database Queries

Check command status:
```sql
SELECT id, name, status, retries, last_error
FROM command
WHERE idempotency_key = 'test-1';
```

Check outbox:
```sql
SELECT id, category, topic, status, attempts
FROM outbox
WHERE status != 'PUBLISHED'
ORDER BY created_at DESC;
```

Check DLQ:
```sql
SELECT command_name, error_message, parked_at
FROM command_dlq
ORDER BY parked_at DESC;
```

### IBM MQ Web Console

Open https://localhost:9443 (user: admin, password: passw0rd)

## Design Principles

1. **Transactional Integrity** - Commands and outbox entries are inserted in a single transaction
2. **At-Least-Once Delivery** - Guaranteed delivery via outbox pattern
3. **Idempotency** - Inbox table prevents duplicate processing
4. **Low Latency** - Fast-path publishing via after-commit hook
5. **Resilience** - Background sweep handles failures
6. **Observability** - Full audit trail in database

## License

MIT
