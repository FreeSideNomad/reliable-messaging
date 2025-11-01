# Load Testing Guide

This guide explains how to run load tests against the Reliable Messaging application.

## Quick Start

The script automatically loads configuration from `.env` file in the project root.

```bash
# Run a 100 TPS load test for 60 seconds
./run-load-test.sh 100 60

# Run a 50 TPS load test for 120 seconds
./run-load-test.sh 50 120

# Run with default values (100 TPS, 60s)
./run-load-test.sh
```

## Prerequisites

### 1. Install vegeta

```bash
brew install vegeta
```

### 2. Start the infrastructure

```bash
docker-compose up -d
```

### 3. Start the application

```bash
# In a separate terminal
mvn mn:run
```

Or run in background:

```bash
mvn mn:run > app.log 2>&1 &
```

## Usage

```bash
./run-load-test.sh [TPS] [DURATION_SECONDS]
```

### Parameters

- **TPS** (optional): Transactions per second (default: 100)
- **DURATION_SECONDS** (optional): Test duration in seconds (default: 60)

### Configuration

The script automatically reads configuration from `.env` file:

```bash
# .env file (automatically loaded)
MICRONAUT_SERVER_PORT=8080
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=reliable
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

You can override values using environment variables:

```bash
# Override application URL
APP_URL=http://localhost:9090 ./run-load-test.sh 100 60

# Override PostgreSQL settings
POSTGRES_CONTAINER=my-postgres ./run-load-test.sh 100 60
POSTGRES_PORT=5433 ./run-load-test.sh 100 60

# Override multiple settings
MICRONAUT_SERVER_PORT=9090 POSTGRES_DB=test ./run-load-test.sh 100 60
```

**Values used (in priority order):**
1. Environment variables (highest priority)
2. `.env` file values
3. Script defaults (fallback)

## What the Script Does

1. **Pre-flight Checks**
   - Verifies vegeta is installed
   - Checks if the application is running
   - Validates PostgreSQL connectivity

2. **Database Cleanup**
   - Truncates all tables for a clean test

3. **Test Preparation**
   - Generates unique vegeta targets with idempotency keys
   - Creates request body file

4. **Load Test Execution**
   - Runs vegeta attack at specified TPS
   - Records all results

5. **Report Generation**
   - Generates text, JSON, and HDR histogram reports
   - Queries database for verification
   - Creates comprehensive markdown report

6. **Database Verification**
   - Command processing stats
   - Outbox publishing performance
   - Fast path publisher latency metrics
   - DLQ and inbox counts

## Output

All test results are saved in timestamped directories:

```
load-test-results/
└── 20251101_010257_100tps/
    ├── REPORT.md                      # Main comprehensive report
    ├── report.txt                     # Vegeta text report
    ├── report.json                    # Vegeta JSON report
    ├── histogram.txt                  # HDR histogram
    ├── results.bin                    # Raw vegeta results
    ├── targets.txt                    # Request targets used
    ├── body.json                      # Request body
    ├── db_command_status.txt          # Command status counts
    ├── db_outbox_status.txt           # Outbox status counts
    ├── db_publisher_performance.txt   # Fast path metrics
    └── db_misc_counts.txt             # DLQ and inbox counts
```

## Reading the Report

The main report (`REPORT.md`) includes:

### HTTP Performance
- Request success rate
- Latency percentiles (P50, P90, P95, P99)
- Throughput vs target rate

### Database Verification
- Command processing success rate
- Outbox publishing completeness
- Fast path publisher performance (crucial!)
- Error rates (DLQ entries)

### Pass/Fail Criteria

The report automatically evaluates:
- ✅ HTTP Success Rate: Should be 100%
- ✅ Command Processing: All commands should succeed
- ✅ Outbox Publishing: All 3 entries per command published
- ✅ Error Handling: Zero DLQ entries

## Example Scenarios

### Baseline Performance Test
```bash
# Standard 100 TPS for 1 minute
./run-load-test.sh 100 60
```

### Peak Load Test
```bash
# High throughput test
./run-load-test.sh 500 60
```

### Sustained Load Test
```bash
# Lower TPS but longer duration
./run-load-test.sh 50 300
```

### Quick Smoke Test
```bash
# Fast validation
./run-load-test.sh 10 10
```

## Interpreting Fast Path Publisher Metrics

The most important metrics are in the "Fast Path Publisher Performance" section:

```
Category | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms)
---------|----------|----------|----------|----------
command  | 0.77     | 0.68     | 1.21     | 1.76
event    | 1.09     | 1.01     | 1.51     | 2.03
reply    | 1.09     | 1.01     | 1.51     | 2.01
```

**Good Performance**: P95 < 5ms (publishing happens within milliseconds of commit)
**Excellent Performance**: P95 < 2ms (like the example above)
**Problem**: P95 > 100ms (fast path publisher may not be working)

## Troubleshooting

### Application Not Running
```
[ERROR] Application is not running at http://localhost:8080
```

**Solution**: Start the app with `mvn mn:run`

### PostgreSQL Not Accessible
```
[ERROR] Cannot connect to PostgreSQL container: reliable-postgres
```

**Solution**:
```bash
docker-compose up -d postgres
# or
docker ps | grep postgres  # verify container name
```

### vegeta Not Found
```
[ERROR] vegeta not found. Install with: brew install vegeta
```

**Solution**: `brew install vegeta`

### High Error Rate

If you see errors in the load test:

1. Check application logs: `tail -f app.log`
2. Check database connection pool settings
3. Verify infrastructure is healthy: `docker-compose ps`
4. Look for database deadlocks or connection exhaustion

## Comparing Test Runs

To compare multiple test runs:

```bash
# Run baseline
./run-load-test.sh 100 60

# Make code changes...

# Run comparison test
./run-load-test.sh 100 60

# Compare the REPORT.md files in the two result directories
```

## Automation

The script can be integrated into CI/CD pipelines:

```bash
#!/bin/bash
# ci-load-test.sh

# Start infrastructure
docker-compose up -d

# Wait for services
sleep 10

# Start app in background
mvn mn:run > app.log 2>&1 &
APP_PID=$!

# Wait for app to start
sleep 20

# Run load test
./run-load-test.sh 100 30

# Capture exit code
RESULT=$?

# Cleanup
kill $APP_PID
docker-compose down

exit $RESULT
```

## Tips for Best Results

1. **Warm Up**: Run a small test first to warm up the JVM
   ```bash
   ./run-load-test.sh 10 10  # warm up
   ./run-load-test.sh 100 60 # actual test
   ```

2. **Monitor Resources**: Keep an eye on CPU, memory, and connections
   ```bash
   docker stats
   ```

3. **Database Cleanup**: The script cleans the DB automatically, but you can also do it manually:
   ```bash
   docker exec reliable-postgres psql -U postgres -d reliable \
     -c "TRUNCATE TABLE command, inbox, outbox, command_dlq;"
   ```

4. **Multiple Runs**: For statistical significance, run multiple tests:
   ```bash
   for i in {1..3}; do
     ./run-load-test.sh 100 60
     sleep 30  # cooldown between tests
   done
   ```

## Contributing

To modify the load test script:

1. Edit `run-load-test.sh`
2. Test your changes with a quick run: `./run-load-test.sh 10 10`
3. Verify the report format and accuracy

## Support

For issues or questions:
- Check the application logs
- Review the generated `REPORT.md`
- Examine the raw vegeta results: `vegeta report load-test-results/<timestamp>/results.bin`
