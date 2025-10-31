#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "🛑 Stopping Reliable Messaging Infrastructure..."
echo ""

docker-compose down

echo ""
echo -e "${GREEN}✓ Infrastructure stopped${NC}"
echo ""
echo -e "${YELLOW}💡 Tip: To remove volumes (delete all data), run:${NC}"
echo "   docker-compose down -v"
