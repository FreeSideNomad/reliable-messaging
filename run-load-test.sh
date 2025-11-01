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

    # Check if server responds (any HTTP response is OK, even 404)
    if ! curl -s -o /dev/null -w "%{http_code}" "$APP_URL" 2>&1 | grep -q "[0-9][0-9][0-9]"; then
        log_error "Application is not running at $APP_URL"
        log_info "Start the application with: mvn mn:run"
        exit 1
    fi

    log_success "Application is running"
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

generate_vegeta_targets() {
    log_info "Generating $TOTAL_REQUESTS vegeta targets..."

    mkdir -p "$OUTPUT_DIR"

    # Create request body
    cat > "$OUTPUT_DIR/body.json" <<EOF
{"username":"vegeta-load-user"}
EOF

    # Generate targets file
    > "$OUTPUT_DIR/targets.txt"

    for i in $(seq 1 $TOTAL_REQUESTS); do
        # Use nanosecond timestamp + counter for unique idempotency keys
        NANOS=$(date +%s%N)
        cat >> "$OUTPUT_DIR/targets.txt" <<EOF
POST ${APP_URL}/commands/CreateUser
Content-Type: application/json
Idempotency-Key: loadtest-${TIMESTAMP}-${i}-${NANOS}
@${OUTPUT_DIR}/body.json

EOF
    done

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

    # Clean slate
    clean_database

    # Prepare test
    generate_vegeta_targets

    # Execute load test
    run_load_test

    # Generate reports
    generate_reports
    query_database_stats
    generate_markdown_report

    # Show summary
    print_summary
}

# Run main function
main "$@"
