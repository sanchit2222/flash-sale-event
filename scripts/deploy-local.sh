#!/bin/bash

# Flash Sale System - Local Deployment Script
# This script helps you quickly deploy the Flash Sale system locally using Docker Compose

set -e

echo "====================================="
echo "Flash Sale System - Local Deployment"
echo "====================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Error: Docker is not installed. Please install Docker first.${NC}"
    exit 1
fi

# Check if Docker Compose is installed
if ! command -v docker-compose &> /dev/null; then
    echo -e "${RED}Error: Docker Compose is not installed. Please install Docker Compose first.${NC}"
    exit 1
fi

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed. Please install Maven 3.9+ first.${NC}"
    exit 1
fi

echo -e "${YELLOW}Step 1: Creating .env file...${NC}"
if [ ! -f .env ]; then
    cat > .env << EOF
# Database Configuration
DB_PASSWORD=flashsale_pass_local

# Redis Configuration
REDIS_PASSWORD=redis_pass_local

# AWS Configuration (for local testing)
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
EOF
    echo -e "${GREEN}✓ .env file created${NC}"
else
    echo -e "${GREEN}✓ .env file already exists${NC}"
fi

echo ""
echo -e "${YELLOW}Step 2: Building application JAR...${NC}"
mvn clean package -DskipTests
echo -e "${GREEN}✓ Application built successfully${NC}"

echo ""
echo -e "${YELLOW}Step 3: Starting Docker containers...${NC}"
docker-compose up -d
echo -e "${GREEN}✓ Docker containers started${NC}"

echo ""
echo -e "${YELLOW}Step 4: Waiting for services to be ready...${NC}"
echo "This may take 1-2 minutes..."

# Wait for PostgreSQL
echo -n "Waiting for PostgreSQL..."
for i in {1..30}; do
    if docker-compose exec -T postgres pg_isready -U flashsale_user -d flashsale &> /dev/null; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

# Wait for Redis
echo -n "Waiting for Redis..."
for i in {1..30}; do
    if docker-compose exec -T redis redis-cli --pass flashsale_pass_local ping &> /dev/null; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

# Wait for Application
echo -n "Waiting for Application..."
for i in {1..60}; do
    if curl -s http://localhost:8080/actuator/health &> /dev/null; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

echo ""
echo -e "${GREEN}====================================="
echo "  Deployment Successful!"
echo "=====================================${NC}"
echo ""
echo "Services are now running:"
echo ""
echo "  Application:     http://localhost:8080"
echo "  Health Check:    http://localhost:8080/actuator/health"
echo "  Kafka UI:        http://localhost:8081"
echo "  PostgreSQL:      localhost:5432"
echo "  Redis:           localhost:6379"
echo "  Kafka:           localhost:9093"
echo ""
echo "Useful commands:"
echo "  View logs:       docker-compose logs -f app"
echo "  Stop services:   docker-compose down"
echo "  Restart:         docker-compose restart app"
echo ""
echo -e "${YELLOW}Note: Check DEPLOYMENT.md for more detailed instructions${NC}"
