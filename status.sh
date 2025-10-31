#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "📊 Infrastructure Status"
echo ""

# Check Docker Compose services
echo "Docker Compose Services:"
docker-compose ps

echo ""
echo "Health Checks:"
echo ""

# Check PostgreSQL
if docker exec reliable-postgres pg_isready -U postgres > /dev/null 2>&1; then
    echo -e "  PostgreSQL:  ${GREEN}✓ Healthy${NC}"
else
    echo -e "  PostgreSQL:  ${RED}✗ Not healthy${NC}"
fi

# Check IBM MQ
if docker exec reliable-ibmmq dspmq | grep -q "RUNNING" 2>/dev/null; then
    echo -e "  IBM MQ:      ${GREEN}✓ Healthy${NC}"
else
    echo -e "  IBM MQ:      ${YELLOW}⚠ Not healthy${NC}"
fi

# Check Kafka
if docker exec reliable-kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    echo -e "  Kafka:       ${GREEN}✓ Healthy${NC}"
else
    echo -e "  Kafka:       ${YELLOW}⚠ Not healthy${NC}"
fi

echo ""
echo "Connection URLs:"
echo "  PostgreSQL:  postgresql://localhost:${POSTGRES_PORT:-5432}/reliable"
echo "  IBM MQ:      localhost:${MQ_PORT:-1414}"
echo "  Kafka:       localhost:${KAFKA_PORT:-9092}"
