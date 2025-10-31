#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "🚀 Starting Reliable Messaging Infrastructure..."
echo ""

# Check if .env exists
if [ ! -f .env ]; then
    echo -e "${YELLOW}⚠️  Warning: .env file not found${NC}"
    echo ""
    echo "Creating .env from .env.example..."

    if [ -f .env.example ]; then
        cp .env.example .env
        echo -e "${GREEN}✓ Created .env file${NC}"
        echo ""
        echo -e "${YELLOW}⚠️  IMPORTANT: Please edit .env and set your passwords before continuing!${NC}"
        echo ""
        echo "Edit the following variables in .env:"
        echo "  - POSTGRES_PASSWORD"
        echo "  - MQ_PASS"
        echo "  - MQ_APP_PASSWORD"
        echo "  - MQ_ADMIN_PASSWORD"
        echo ""
        read -p "Press Enter after you've updated .env, or Ctrl+C to cancel..."
    else
        echo -e "${RED}✗ Error: .env.example not found${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}✓ Found .env file${NC}"
echo ""

# Load .env to validate
export $(cat .env | grep -v '^#' | xargs)

# Validate required variables
REQUIRED_VARS=("POSTGRES_PASSWORD" "MQ_PASS" "MQ_APP_PASSWORD" "MQ_ADMIN_PASSWORD")
MISSING_VARS=()

for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var}" ]; then
        MISSING_VARS+=("$var")
    fi
done

if [ ${#MISSING_VARS[@]} -ne 0 ]; then
    echo -e "${RED}✗ Error: Missing required environment variables:${NC}"
    for var in "${MISSING_VARS[@]}"; do
        echo "  - $var"
    done
    echo ""
    echo "Please set these in your .env file"
    exit 1
fi

echo -e "${GREEN}✓ Environment variables validated${NC}"
echo ""

# Start Docker Compose
echo "Starting services with Docker Compose..."
echo ""

docker-compose up -d

echo ""
echo -e "${GREEN}✓ Infrastructure started successfully!${NC}"
echo ""
echo "Services running:"
echo "  📊 PostgreSQL:  localhost:${POSTGRES_PORT:-5432}"
echo "  📬 IBM MQ:      localhost:${MQ_PORT:-1414}"
echo "  🌐 MQ Console:  https://localhost:9443"
echo "  📨 Kafka:       localhost:${KAFKA_PORT:-9092}"
echo ""
echo "Next steps:"
echo "  1. Wait ~30 seconds for services to be fully ready"
echo "  2. Run: mvn mn:run"
echo "  3. Test: curl -X POST http://localhost:8080/commands/CreateUser \\"
echo "           -H 'Content-Type: application/json' \\"
echo "           -H 'Idempotency-Key: test-123' \\"
echo "           -d '{\"email\":\"test@example.com\"}'"
echo ""
echo "To view logs: docker-compose logs -f"
echo "To stop:      docker-compose down"
