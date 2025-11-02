#!/bin/bash
set -e

################################################################################
# Reliable Messaging Load Test Script
#
# Usage: ./run-load-test.sh [TPS] [DURATION_SECONDS]
# Example: ./run-load-test.sh 100 60
#
# Generates:
# - Vegeta attack results
# - Latency histogram
# - Database verification
# - Comprehensive markdown report
################################################################################

# Load environment variables from .env file (only if not already set)
if [ -f .env ]; then
    while IFS='=' read -r key value; do
        # Skip comments and empty lines
        [[ $key =~ ^#.*$ ]] && continue
        [[ -z $key ]] && continue
        # Only set if not already set in environment
        if [ -z "${!key}" ]; then
            export "$key=$value"
        fi
    done < .env
fi

# Default values
TPS=${1:-100}
DURATION=${2:-60}
TOTAL_REQUESTS=$((TPS * DURATION))

# Application URL (priority: env var > .env > default)
APP_PORT=${MICRONAUT_SERVER_PORT:-8080}
APP_URL=${APP_URL:-http://localhost:${APP_PORT}}

# PostgreSQL settings (priority: env var > .env > default)
POSTGRES_HOST=${POSTGRES_HOST:-localhost}
POSTGRES_PORT=${POSTGRES_PORT:-5432}
POSTGRES_DB=${POSTGRES_DB:-reliable}
POSTGRES_USER=${POSTGRES_USER:-postgres}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-postgres}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-reliable-postgres}

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_DIR="load-test-results/${TIMESTAMP}_${TPS}tps"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

################################################################################
# Helper Functions
################################################################################

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_dependencies() {
    log_info "Checking dependencies..."

    if ! command -v vegeta &> /dev/null; then
        log_error "vegeta not found. Install with: brew install vegeta"
        exit 1
    fi

    if ! command -v docker &> /dev/null; then
        log_error "docker not found. Please install Docker."
        exit 1
    fi

    log_success "All dependencies present"
}

check_app_running() {
    log_info "Checking if application is running at $APP_URL..."

    local MAX_RETRIES=30
    local RETRY_COUNT=0
    local WAIT_SECONDS=2

    while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
        # Try to connect to the health endpoint, suppress curl errors
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 2 "$APP_URL/health" 2>/dev/null || echo "000")

        # Check if we got a valid HTTP response code (2xx, 3xx, 4xx, 5xx)
        if [ "$HTTP_CODE" != "000" ] && [ "$HTTP_CODE" != "" ]; then
            if echo "$HTTP_CODE" | grep -qE "^[2-5][0-9][0-9]$"; then
                log_success "Application is running (HTTP $HTTP_CODE)"
                return 0
            fi
        fi

        RETRY_COUNT=$((RETRY_COUNT + 1))
        if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
            echo -ne "  ⏳ Waiting for application... attempt $RETRY_COUNT/$MAX_RETRIES (got: $HTTP_CODE)\r"
            sleep $WAIT_SECONDS
        fi
    done

    echo "" # New line after progress
    log_error "Application is not responding at $APP_URL after $((MAX_RETRIES * WAIT_SECONDS)) seconds"
    log_info "Last response code: $HTTP_CODE"
    log_info ""
    log_info "Troubleshooting:"
    log_info "  1. Start the application: mvn mn:run"
    log_info "  2. Check application logs for errors"
    log_info "  3. Verify port 8080 is not blocked: lsof -ti:8080"
    log_info "  4. Test manually: curl -v $APP_URL/health"
    exit 1
}

verify_api_working() {
    log_info "Verifying API is functional..."

    # Make a test API call to ensure endpoint is working
    local TEMP_FILE=$(mktemp)
    local HTTP_CODE=$(curl -s -w "%{http_code}" -o "$TEMP_FILE" -X POST "$APP_URL/commands/CreateUser" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: healthcheck-$(date +%s)" \
        -d '{"username":"healthcheck-user"}' 2>/dev/null || echo "000")

    local RESPONSE_BODY=$(cat "$TEMP_FILE" 2>/dev/null)
    rm -f "$TEMP_FILE"

    # Accept 200, 201, or 202 as success
    if echo "$HTTP_CODE" | grep -qE "^(200|201|202)$"; then
        log_success "API endpoint is functional (HTTP $HTTP_CODE)"
        return 0
    else
        log_error "API endpoint returned HTTP $HTTP_CODE"
        if [ -n "$RESPONSE_BODY" ]; then
            echo ""
            echo "Response body:"
            echo "$RESPONSE_BODY" | head -20
            echo ""
        fi
        log_error "API is not functional - aborting load test"
        log_info "Check application logs for errors"
        exit 1
    fi
}

check_postgres() {
    log_info "Checking PostgreSQL connection..."

    if ! docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1;" > /dev/null 2>&1; then
        log_error "Cannot connect to PostgreSQL (container: $POSTGRES_CONTAINER, db: $POSTGRES_DB, user: $POSTGRES_USER)"
        exit 1
    fi

    log_success "PostgreSQL is accessible"
}

clean_database() {
    log_info "Cleaning database tables..."

    docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
        "TRUNCATE TABLE command, inbox, outbox, command_dlq;" > /dev/null 2>&1

    log_success "Database cleaned"
}

run_warmup_test() {
    log_info "Running warmup test: 10 TPS for 20 seconds (200 requests)..."

    local WARMUP_DIR="$OUTPUT_DIR/warmup"
    mkdir -p "$WARMUP_DIR"

    # Create warmup request body
    cat > "$WARMUP_DIR/body.json" <<EOF
{"username":"warmup-test-user"}
EOF

    # Generate warmup targets
    awk -v url="$APP_URL" \
        -v timestamp="warmup-$TIMESTAMP" \
        -v body="$WARMUP_DIR/body.json" \
        'BEGIN {
            for (i = 1; i <= 200; i++) {
                printf "POST %s/commands/CreateUser\n", url
                printf "Content-Type: application/json\n"
                printf "Idempotency-Key: warmup-%s-%d\n", timestamp, i
                printf "@%s\n\n", body
            }
        }' > "$WARMUP_DIR/targets.txt"

    # Run warmup load test
    vegeta attack \
        -targets="$WARMUP_DIR/targets.txt" \
        -rate="10/s" \
        -duration="20s" \
        -timeout=10s \
        > "$WARMUP_DIR/results.bin"

    # Generate warmup report
    vegeta report "$WARMUP_DIR/results.bin" > "$WARMUP_DIR/report.txt"

    log_success "Warmup test completed"
}

verify_warmup_results() {
    log_info "Waiting 20 seconds for warmup processing to complete..."
    sleep 20

    log_info "Verifying warmup test results..."

    # Check for errors in warmup
    local WARMUP_REPORT=$(cat "$OUTPUT_DIR/warmup/report.txt")
    local WARMUP_SUCCESS=$(echo "$WARMUP_REPORT" | grep "Success" | awk '{print $3}')
    local WARMUP_ERRORS=$(echo "$WARMUP_REPORT" | grep "Error Set" -A 10)

    # Check database for failed commands or DLQ entries
    local CMD_FAILED=$(docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c \
        "SELECT COUNT(*) FROM command WHERE status = 'FAILED';" | tr -d '[:space:]')
    local DLQ_COUNT=$(docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c \
        "SELECT COUNT(*) FROM command_dlq;" | tr -d '[:space:]')

    echo ""
    echo "Warmup Test Results:"
    echo "  Success Rate: $WARMUP_SUCCESS"
    echo "  Failed Commands: $CMD_FAILED"
    echo "  DLQ Entries: $DLQ_COUNT"
    echo ""

    # Check if warmup was clean
    if [ "$WARMUP_SUCCESS" != "100.00%" ] || [ "$CMD_FAILED" != "0" ] || [ "$DLQ_COUNT" != "0" ]; then
        log_error "Warmup test failed! System is not healthy."
        echo ""
        echo "Error details:"
        if [ "$WARMUP_SUCCESS" != "100.00%" ]; then
            echo "  - HTTP success rate: $WARMUP_SUCCESS (expected 100.00%)"
            echo ""
            echo "$WARMUP_ERRORS"
        fi
        if [ "$CMD_FAILED" != "0" ]; then
            echo "  - Failed commands in database: $CMD_FAILED"
        fi
        if [ "$DLQ_COUNT" != "0" ]; then
            echo "  - Dead letter queue entries: $DLQ_COUNT"
        fi
        echo ""
        log_error "Aborting full load test. Please check the system health."
        exit 1
    fi

    log_success "Warmup test passed - system is healthy"
}

generate_vegeta_targets() {
    log_info "Generating $TOTAL_REQUESTS vegeta targets..."

    mkdir -p "$OUTPUT_DIR"

    # Create request body
    cat > "$OUTPUT_DIR/body.json" <<EOF
{"username":"vegeta-load-user"}
EOF

    # Generate targets file using awk (MUCH faster than loop)
    # This generates all targets in one shot
    awk -v url="$APP_URL" \
        -v timestamp="$TIMESTAMP" \
        -v body="$OUTPUT_DIR/body.json" \
        -v total="$TOTAL_REQUESTS" \
        'BEGIN {
            for (i = 1; i <= total; i++) {
                printf "POST %s/commands/CreateUser\n", url
                printf "Content-Type: application/json\n"
                printf "Idempotency-Key: loadtest-%s-%d\n", timestamp, i
                printf "@%s\n\n", body
            }
        }' > "$OUTPUT_DIR/targets.txt"

    log_success "Generated $TOTAL_REQUESTS unique targets"
}

run_load_test() {
    log_info "Starting load test: ${TPS} TPS for ${DURATION}s (${TOTAL_REQUESTS} total requests)..."
    echo ""

    vegeta attack \
        -targets="$OUTPUT_DIR/targets.txt" \
        -rate="${TPS}/s" \
        -duration="${DURATION}s" \
        -timeout=10s \
        > "$OUTPUT_DIR/results.bin"

    log_success "Load test completed"
}

generate_reports() {
    log_info "Generating reports..."

    # Text report
    vegeta report "$OUTPUT_DIR/results.bin" > "$OUTPUT_DIR/report.txt"

    # HDR histogram
    vegeta report -type=hdrplot "$OUTPUT_DIR/results.bin" > "$OUTPUT_DIR/histogram.txt"

    # JSON report for programmatic access
    vegeta report -type=json "$OUTPUT_DIR/results.bin" > "$OUTPUT_DIR/report.json"

    log_success "Reports generated"
}

query_database_stats() {
    log_info "Querying database for verification..."

    # Command status counts
    docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c \
        "SELECT status::text, COUNT(*) FROM command GROUP BY status;" \
        > "$OUTPUT_DIR/db_command_status.txt"

    # Outbox status counts
    docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c \
        "SELECT status, COUNT(*) FROM outbox GROUP BY status;" \
        > "$OUTPUT_DIR/db_outbox_status.txt"

    # Fast path publisher performance
    docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c \
        "SELECT
          category,
          COUNT(*) as total,
          ROUND(AVG(EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::numeric, 2) as avg_ms,
          ROUND(MIN(EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::numeric, 2) as min_ms,
          ROUND(MAX(EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::numeric, 2) as max_ms,
          ROUND(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::numeric, 2) as p50_ms,
          ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::numeric, 2) as p95_ms,
          ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (published_at - created_at)) * 1000)::numeric, 2) as p99_ms
        FROM outbox
        GROUP BY category
        ORDER BY category;" \
        > "$OUTPUT_DIR/db_publisher_performance.txt"

    # DLQ and Inbox counts
    docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c \
        "SELECT 'DLQ', COUNT(*) FROM command_dlq
         UNION ALL
         SELECT 'Inbox', COUNT(*) FROM inbox;" \
        > "$OUTPUT_DIR/db_misc_counts.txt"

    log_success "Database stats collected"
}

generate_markdown_report() {
    log_info "Generating markdown report..."

    # Extract metrics from vegeta report
    local REPORT=$(cat "$OUTPUT_DIR/report.txt")

    # Parse key metrics
    local REQUESTS=$(echo "$REPORT" | grep "Requests" | awk '{print $3}')
    local RATE=$(echo "$REPORT" | grep "Requests" | awk '{print $5}')
    local THROUGHPUT=$(echo "$REPORT" | grep "Requests" | awk '{print $7}')
    local DURATION_ACTUAL=$(echo "$REPORT" | grep "Duration" | awk '{print $3}')
    local LATENCY_MIN=$(echo "$REPORT" | grep "Latencies" | awk '{print $3}')
    local LATENCY_MEAN=$(echo "$REPORT" | grep "Latencies" | awk '{print $5}')
    local LATENCY_50=$(echo "$REPORT" | grep "Latencies" | awk '{print $7}')
    local LATENCY_90=$(echo "$REPORT" | grep "Latencies" | awk '{print $9}')
    local LATENCY_95=$(echo "$REPORT" | grep "Latencies" | awk '{print $11}')
    local LATENCY_99=$(echo "$REPORT" | grep "Latencies" | awk '{print $13}')
    local LATENCY_MAX=$(echo "$REPORT" | grep "Latencies" | awk '{print $15}')
    local SUCCESS_RATIO=$(echo "$REPORT" | grep "Success" | awk '{print $3}')

    # Get DB stats
    local CMD_SUCCEEDED=$(grep "SUCCEEDED" "$OUTPUT_DIR/db_command_status.txt" | awk '{print $3}' || echo "0")
    local CMD_FAILED=$(grep "FAILED" "$OUTPUT_DIR/db_command_status.txt" | awk '{print $3}' || echo "0")
    local OUTBOX_PUBLISHED=$(grep "PUBLISHED" "$OUTPUT_DIR/db_outbox_status.txt" | awk '{print $2}' || echo "0")
    local DLQ_COUNT=$(grep "DLQ" "$OUTPUT_DIR/db_misc_counts.txt" | awk '{print $3}' || echo "0")
    local INBOX_COUNT=$(grep "Inbox" "$OUTPUT_DIR/db_misc_counts.txt" | awk '{print $3}' || echo "0")

    cat > "$OUTPUT_DIR/REPORT.md" <<EOF
# Load Test Report - Reliable Messaging System

**Test ID**: ${TIMESTAMP}
**Generated**: $(date)
**Target**: ${APP_URL}

---

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Tool | Vegeta $(vegeta -version 2>&1) |
| Target TPS | ${TPS} |
| Duration | ${DURATION}s |
| Total Requests | ${TOTAL_REQUESTS} |
| Timeout | 10s |
| Unique Idempotency Keys | Yes |

---

## HTTP Performance Results

### Summary Metrics

| Metric | Value |
|--------|-------|
| Requests Sent | ${REQUESTS} |
| Actual Rate | ${RATE} req/s |
| Throughput | ${THROUGHPUT} req/s |
| Success Rate | ${SUCCESS_RATIO} |
| Duration | ${DURATION_ACTUAL} |

### Latency Distribution

| Percentile | Latency |
|------------|---------|
| Min | ${LATENCY_MIN} |
| Mean | ${LATENCY_MEAN} |
| P50 (Median) | ${LATENCY_50} |
| P90 | ${LATENCY_90} |
| P95 | ${LATENCY_95} |
| P99 | ${LATENCY_99} |
| Max | ${LATENCY_MAX} |

### Detailed Vegeta Report

\`\`\`
$(cat "$OUTPUT_DIR/report.txt")
\`\`\`

---

## Database Verification

### Command Processing

| Status | Count |
|--------|-------|
| SUCCEEDED | ${CMD_SUCCEEDED} |
| FAILED | ${CMD_FAILED} |
| **DLQ Entries** | ${DLQ_COUNT} |

**Inbox Entries**: ${INBOX_COUNT} (deduplication check)

### Outbox Publishing

**Total Published**: ${OUTBOX_PUBLISHED}

Expected: $((TOTAL_REQUESTS * 3)) (3 outbox entries per command: request + reply + event)

### Fast Path Publisher Performance

| Category | Total | Avg (ms) | Min (ms) | Max (ms) | P50 (ms) | P95 (ms) | P99 (ms) |
|----------|-------|----------|----------|----------|----------|----------|----------|
$(cat "$OUTPUT_DIR/db_publisher_performance.txt")

---

## Analysis

### Success Criteria

EOF

    # Add pass/fail analysis
    if [ "$SUCCESS_RATIO" == "100.00%" ]; then
        echo "✅ **HTTP Success Rate**: PASS (${SUCCESS_RATIO})" >> "$OUTPUT_DIR/REPORT.md"
    else
        echo "❌ **HTTP Success Rate**: FAIL (${SUCCESS_RATIO})" >> "$OUTPUT_DIR/REPORT.md"
    fi

    if [ "$CMD_SUCCEEDED" == "$TOTAL_REQUESTS" ]; then
        echo "✅ **Command Processing**: PASS (all ${TOTAL_REQUESTS} succeeded)" >> "$OUTPUT_DIR/REPORT.md"
    else
        echo "❌ **Command Processing**: FAIL (${CMD_SUCCEEDED}/${TOTAL_REQUESTS} succeeded)" >> "$OUTPUT_DIR/REPORT.md"
    fi

    local EXPECTED_OUTBOX=$((TOTAL_REQUESTS * 3))
    if [ "$OUTBOX_PUBLISHED" == "$EXPECTED_OUTBOX" ]; then
        echo "✅ **Outbox Publishing**: PASS (all ${OUTBOX_PUBLISHED} published)" >> "$OUTPUT_DIR/REPORT.md"
    else
        echo "❌ **Outbox Publishing**: FAIL (${OUTBOX_PUBLISHED}/${EXPECTED_OUTBOX} published)" >> "$OUTPUT_DIR/REPORT.md"
    fi

    if [ "$DLQ_COUNT" == "0" ]; then
        echo "✅ **Error Handling**: PASS (no DLQ entries)" >> "$OUTPUT_DIR/REPORT.md"
    else
        echo "⚠️ **Error Handling**: ${DLQ_COUNT} entries in DLQ" >> "$OUTPUT_DIR/REPORT.md"
    fi

    cat >> "$OUTPUT_DIR/REPORT.md" <<EOF

### Performance Observations

- **Actual vs Target Rate**: ${RATE} req/s vs ${TPS} req/s target
- **P95 Latency**: ${LATENCY_95}
- **P99 Latency**: ${LATENCY_99}

---

## Files Generated

This test generated the following files in \`${OUTPUT_DIR}\`:

- \`REPORT.md\` - This comprehensive report
- \`report.txt\` - Vegeta text report
- \`report.json\` - Vegeta JSON report (for automation)
- \`histogram.txt\` - HDR histogram data
- \`results.bin\` - Raw vegeta results
- \`targets.txt\` - Request targets used
- \`db_*.txt\` - Database query results

---

## Rerun This Test

\`\`\`bash
./run-load-test.sh ${TPS} ${DURATION}
\`\`\`

EOF

    log_success "Markdown report generated: $OUTPUT_DIR/REPORT.md"
}

print_summary() {
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "                    LOAD TEST COMPLETE                          "
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "  Test ID:      ${TIMESTAMP}"
    echo "  Target TPS:   ${TPS}"
    echo "  Duration:     ${DURATION}s"
    echo "  Total Reqs:   ${TOTAL_REQUESTS}"
    echo ""
    echo "  Results Dir:  ${OUTPUT_DIR}"
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "View the full report:"
    echo "  cat ${OUTPUT_DIR}/REPORT.md"
    echo ""
    echo "Or open in your editor:"
    echo "  code ${OUTPUT_DIR}/REPORT.md"
    echo ""
}

################################################################################
# Main Execution
################################################################################

main() {
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo "          Reliable Messaging Load Test Runner                  "
    echo "════════════════════════════════════════════════════════════════"
    echo ""
    echo "  TPS:          ${TPS}"
    echo "  Duration:     ${DURATION}s"
    echo "  Total Reqs:   ${TOTAL_REQUESTS}"
    echo "  Target:       ${APP_URL}"
    echo "  Timestamp:    ${TIMESTAMP}"
    echo ""
    echo "  Configuration (from .env):"
    echo "    DB Host:    ${POSTGRES_HOST}:${POSTGRES_PORT}"
    echo "    DB Name:    ${POSTGRES_DB}"
    echo "    DB User:    ${POSTGRES_USER}"
    echo "    Container:  ${POSTGRES_CONTAINER}"
    echo ""
    echo "════════════════════════════════════════════════════════════════"
    echo ""

    # Pre-flight checks
    check_dependencies
    check_app_running
    check_postgres

    # Verify API is working with a single test request
    verify_api_working

    # Clean slate for warmup
    clean_database

    # Run warmup test to verify system health
    run_warmup_test
    verify_warmup_results

    # Clean database again before full run (warmup test passed)
    log_info "Warmup passed - cleaning database for full test run..."
    clean_database

    # Prepare test
    generate_vegeta_targets

    # Execute load test
    run_load_test

    # Generate reports
    generate_reports

    # Wait for async processing to complete before querying database
    # Default: 30 seconds, can be overridden with PROCESSING_WAIT_TIME env var
    local WAIT_TIME=${PROCESSING_WAIT_TIME:-30}
    if [ "$WAIT_TIME" -gt 0 ]; then
        log_info "Waiting ${WAIT_TIME} seconds for async processing to complete..."
        echo ""
        echo "  This allows the outbox sweep job and command consumers"
        echo "  to process the backlog before generating database reports."
        echo ""

        # Show progress every 30 seconds
        local elapsed=0
        while [ $elapsed -lt $WAIT_TIME ]; do
            local remaining=$((WAIT_TIME - elapsed))
            echo -ne "  ⏳ Processing... ${remaining}s remaining\r"
            sleep 30
            elapsed=$((elapsed + 30))
        done
        echo -ne "\n"
        log_success "Processing wait complete"
    fi

    query_database_stats
    generate_markdown_report

    # Show summary
    print_summary
}

# Run main function
main "$@"
