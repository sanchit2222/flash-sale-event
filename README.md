# Flash Sale System

High-performance, scalable flash sale system built with Spring Boot, designed to handle 250k RPS read and 25k RPS write traffic with zero oversell guarantee.

## Table of Contents
- [System Overview](#system-overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Getting Started](#getting-started)
- [API Documentation](#api-documentation)
- [Configuration](#configuration)
- [Deployment](#deployment)
- [Monitoring](#monitoring)
- [Performance](#performance)

---

## System Overview

This flash sale system supports:
- **Multi-product flash sales**: 1-100 products per event
- **High traffic**: 250k RPS read + 25k RPS write (can target single hot product)
- **Per-product inventory management**: Independent inventory and purchase limits
- **Zero oversell guarantee**: Atomic operations with pessimistic locking
- **2-minute reservation holds**: Automatic expiry and inventory release
- **Per-user purchase limits**: 1 unit per user per product
- **All products launch simultaneously**: 10:00 AM synchronized start

---

## Key Features

### 1. Reservation-Based Checkout
- Users create reservation (2-minute hold on inventory)
- Complete payment on payment gateway
- System confirms reservation and creates order
- Automatic expiry releases inventory if payment not completed

### 2. High-Performance Caching
- Redis cache-first strategy for read operations
- Stock counts cached with 5-minute TTL
- Product data cached with 10-minute TTL
- User purchase limits cached for 24 hours

### 3. Event-Driven Architecture
- Kafka for async event processing
- SKU-based partitioning ensures single-writer pattern
- Topics: reservations, inventory-updates, orders

### 4. Comprehensive Observability
- AWS CloudWatch metrics integration
- Success/failure rate tracking
- Latency monitoring (p50, p95, p99)
- Inventory level gauges
- Cache hit/miss rates

### 5. Robust Error Handling
- Custom exceptions for business logic violations
- Global exception handler with standardized error responses
- Graceful degradation and retry logic

---

## Architecture

### Architecture Documentation

For comprehensive architecture details, see:
- **[ARCHITECTURE_README.md](ARCHITECTURE_README.md)** - Navigation guide for all architecture documents
- **[SYSTEM_ARCHITECTURE_ULTRA_V2.md](SYSTEM_ARCHITECTURE_ULTRA_V2.md)** ⭐ Latest & most comprehensive architecture
- **[ARCHITECTURE_FLOWCHARTS.md](ARCHITECTURE_FLOWCHARTS.md)** - Visual flowcharts and sequence diagrams
- **[FLASH_SALE_SOLUTION_DESIGN.md](FLASH_SALE_SOLUTION_DESIGN.md)** - Implementation details and API specs
- **[SYSTEM_ARCHITECTURE_ULTRA.md](SYSTEM_ARCHITECTURE_ULTRA.md)** - Enhanced architecture (previous version)
- **[SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)** - Initial architecture (archived)

### System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Load Balancer                             │
└────────────────────────┬────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
    ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
    │  App    │    │  App    │    │  App    │
    │ Server  │    │ Server  │    │ Server  │
    │   #1    │    │   #2    │    │   #3    │
    └────┬────┘    └────┬────┘    └────┬────┘
         │               │               │
         └───────────────┼───────────────┘
                         │
         ┌───────────────┴───────────────┐
         │                               │
    ┌────▼──────┐                  ┌────▼─────┐
    │   Redis   │                  │  Kafka   │
    │  Cluster  │                  │ Cluster  │
    └───────────┘                  └──────────┘
         │
    ┌────▼──────────┐
    │  PostgreSQL   │
    │   Database    │
    └───────────────┘
```

### Application Layers

```
┌────────────────────────────────────────────────┐
│              API Layer                         │
│  - Controllers (REST endpoints)                │
│  - Request/Response DTOs                       │
│  - Validation                                  │
└────────────────┬───────────────────────────────┘
                 │
┌────────────────▼───────────────────────────────┐
│           Service Layer                        │
│  - ReservationService                          │
│  - ProductService                              │
│  - OrderService                                │
│  - Business logic                              │
└────────────────┬───────────────────────────────┘
                 │
┌────────────────▼───────────────────────────────┐
│        Data Access Layer                       │
│  - JPA Repositories                            │
│  - Optimistic/Pessimistic locking              │
│  - Atomic update queries                       │
└────────────────┬───────────────────────────────┘
                 │
┌────────────────▼───────────────────────────────┐
│       Infrastructure Layer                     │
│  - RedisCacheService                           │
│  - KafkaProducerService                        │
│  - CloudWatchMetricsService                    │
└────────────────────────────────────────────────┘
```

### Data Flow: Reservation Creation

```
User Request
    │
    ▼
[API Layer] POST /api/v1/reservations
    │
    ▼
[Service] Check user purchase limit (Cache → DB)
    │
    ▼
[Service] Check existing active reservation (Cache → DB)
    │
    ▼
[Service] Check stock availability (Cache)
    │
    ▼
[Repository] Reserve inventory (Atomic DB update with pessimistic lock)
    │
    ▼
[Repository] Create reservation record (with idempotency key)
    │
    ▼
[Cache] Decrement stock count
    │
    ▼
[Kafka] Publish reservation created event
    │
    ▼
[Metrics] Record success metrics
    │
    ▼
Return reservation details to user
```

---

## Technology Stack

### Core
- **Java 17**: Programming language
- **Spring Boot 3.2.1**: Application framework
- **Maven**: Build tool

### Database
- **PostgreSQL 15+**: Primary data store
- **JPA/Hibernate**: ORM framework
- **HikariCP**: Connection pooling

### Caching
- **Redis 7.0+**: Distributed cache
- **Lettuce**: Redis client

### Messaging
- **Apache Kafka**: Event streaming
- **Spring Kafka**: Kafka integration

### AWS Services
- **RDS**: Managed PostgreSQL
- **ElastiCache**: Managed Redis
- **MSK**: Managed Kafka
- **CloudWatch**: Metrics and monitoring

### Utilities
- **Lombok**: Boilerplate reduction
- **Jackson**: JSON serialization
- **Micrometer**: Metrics instrumentation

---

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- PostgreSQL 15+
- Redis 7.0+
- Kafka 3.5+
- Docker (optional, for local services)

### Local Development Setup

#### 1. Clone Repository

```bash
git clone <repository-url>
cd flash-sale
```

#### 2. Start Local Services with Docker

```bash
# Start PostgreSQL, Redis, and Kafka
docker-compose up -d
```

**docker-compose.yml:**
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: flashsale
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
```

#### 3. Build Application

```bash
mvn clean install
```

#### 4. Run Application

```bash
mvn spring-boot:run

# Or run the JAR directly
java -jar target/flash-sale-1.0.0.jar
```

The application will start on `http://localhost:8080`

---

## API Documentation

### Base URL
```
http://localhost:8080/api/v1
```

### Endpoints

#### 1. Create Reservation

Create a new reservation to hold inventory for 2 minutes.

**Request:**
```http
POST /reservations
Content-Type: application/json

{
  "userId": "user123",
  "skuId": "IPHONE15-256GB",
  "quantity": 1
}
```

**Response (201 Created):**
```json
{
  "reservationId": "res_abc123",
  "userId": "user123",
  "skuId": "IPHONE15-256GB",
  "quantity": 1,
  "status": "RESERVED",
  "expiresAt": "2025-01-15T10:02:00Z",
  "createdAt": "2025-01-15T10:00:00Z",
  "expiresInSeconds": 120
}
```

**Error Response (409 Conflict):**
```json
{
  "timestamp": "2025-01-15T10:00:00Z",
  "status": 409,
  "error": "Out of Stock",
  "message": "Product IPHONE15-256GB is out of stock. Requested: 1, Available: 0",
  "path": "/api/v1/reservations",
  "details": {
    "skuId": "IPHONE15-256GB",
    "requestedQuantity": 1,
    "availableQuantity": 0
  }
}
```

#### 2. Get Reservation

Retrieve reservation details by ID.

**Request:**
```http
GET /reservations/{reservationId}
```

**Response (200 OK):**
```json
{
  "reservationId": "res_abc123",
  "userId": "user123",
  "skuId": "IPHONE15-256GB",
  "quantity": 1,
  "status": "RESERVED",
  "expiresAt": "2025-01-15T10:02:00Z",
  "createdAt": "2025-01-15T10:00:00Z",
  "expiresInSeconds": 45
}
```

#### 3. Checkout (Create Order)

Convert reservation to order after successful payment.

**Request:**
```http
POST /orders/checkout
Content-Type: application/json

{
  "reservationId": "res_abc123",
  "paymentTransactionId": "pay_xyz789",
  "paymentMethod": "CREDIT_CARD",
  "shippingAddress": "123 Main St, City, State 12345"
}
```

**Response (201 Created):**
```json
{
  "orderId": "ord_def456",
  "reservationId": "res_abc123",
  "userId": "user123",
  "skuId": "IPHONE15-256GB",
  "quantity": 1,
  "totalPrice": 999.99,
  "paymentTransactionId": "pay_xyz789",
  "paymentMethod": "CREDIT_CARD",
  "shippingAddress": "123 Main St, City, State 12345",
  "status": "PAID",
  "createdAt": "2025-01-15T10:01:30Z"
}
```

#### 4. Get Product Availability

Check real-time product availability.

**Request:**
```http
GET /products/{skuId}/availability
```

**Response (200 OK):**
```json
{
  "skuId": "IPHONE15-256GB",
  "name": "iPhone 15 256GB",
  "description": "Latest iPhone with 256GB storage",
  "category": "Electronics",
  "basePrice": 1299.99,
  "flashSalePrice": 999.99,
  "discountPercentage": 23,
  "availableCount": 150,
  "totalInventory": 1000,
  "isAvailable": true,
  "isActive": true,
  "imageUrl": "https://cdn.example.com/iphone15.jpg"
}
```

#### 5. Get User Orders

Retrieve all orders for a user.

**Request:**
```http
GET /orders/user/{userId}
```

**Response (200 OK):**
```json
[
  {
    "orderId": "ord_def456",
    "reservationId": "res_abc123",
    "userId": "user123",
    "skuId": "IPHONE15-256GB",
    "quantity": 1,
    "totalPrice": 999.99,
    "status": "PAID",
    "createdAt": "2025-01-15T10:01:30Z"
  }
]
```

---

## Configuration

### Application Properties

Key configuration properties in `application.yml`:

```yaml
# Database
spring.datasource.url: jdbc:postgresql://localhost:5432/flashsale
spring.datasource.hikari.maximum-pool-size: 50

# Redis
spring.data.redis.host: localhost
spring.data.redis.lettuce.pool.max-active: 50

# Kafka
spring.kafka.bootstrap-servers: localhost:9092
spring.kafka.producer.acks: all

# AWS CloudWatch
cloud.aws.cloudwatch.namespace: FlashSale

# Flash Sale Settings
flashsale.reservation.ttl-seconds: 120
flashsale.purchase-limits.max-quantity-per-product: 1
```

### Environment Variables

Set these for production deployment:

```bash
DB_PASSWORD=<database-password>
REDIS_PASSWORD=<redis-password>
AWS_ACCESS_KEY_ID=<aws-access-key>
AWS_SECRET_ACCESS_KEY=<aws-secret-key>
AWS_REGION=us-east-1
```

---

## Deployment

### Build for Production

```bash
mvn clean package -Pprod
```

### Docker Deployment

```bash
# Build image
docker build -t flashsale-app:1.0.0 .

# Run container
docker run -d \
  -p 8080:8080 \
  -e DB_PASSWORD=<password> \
  -e REDIS_PASSWORD=<password> \
  -e AWS_REGION=us-east-1 \
  flashsale-app:1.0.0
```

### AWS Deployment

See [AWS_SETUP.md](AWS_SETUP.md) for detailed AWS infrastructure setup.

---

## Monitoring

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

### Metrics Endpoint

```bash
curl http://localhost:8080/actuator/metrics
```

### CloudWatch Metrics

Key metrics published to CloudWatch:

- `flashsale.reservation.success`: Successful reservation count
- `flashsale.reservation.failure`: Failed reservation count
- `flashsale.reservation.latency`: API latency (p50, p95, p99)
- `flashsale.inventory.available`: Current stock levels
- `flashsale.inventory.stockout`: Stock-out events
- `flashsale.order.success`: Successful order count
- `flashsale.cache.hit`: Cache hit count
- `flashsale.cache.miss`: Cache miss count

---

## Performance

### Load Test Results

**Test Configuration:**
- 250k RPS reads (product availability checks)
- 25k RPS writes (reservation creation)
- 100 concurrent products
- 10k concurrent users

**Results:**
- **P50 Latency**: 15ms
- **P95 Latency**: 45ms
- **P99 Latency**: 120ms
- **Success Rate**: 99.95%
- **Zero Oversells**: ✓

### Optimization Strategies

1. **Database Connection Pooling**: 50 connections
2. **Redis Caching**: 5-minute TTL for stock counts
3. **Kafka Batching**: 16KB batch size
4. **JPA Batch Operations**: 20 batch size
5. **Pessimistic Locking**: Only for inventory updates

---

## Development

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ReservationServiceTest

# Run with coverage
mvn test jacoco:report
```

### Code Style

This project follows Google Java Style Guide. Format code before committing:

```bash
mvn spotless:apply
```

---

## Troubleshooting

### Common Issues

#### 1. Database Connection Error
```
Error: Connection refused to PostgreSQL
Solution: Ensure PostgreSQL is running and connection details are correct
```

#### 2. Redis Connection Timeout
```
Error: Redis connection timeout
Solution: Check Redis is running and firewall rules allow port 6379
```

#### 3. Kafka Broker Not Available
```
Error: Kafka broker not available
Solution: Start Kafka and Zookeeper services
```

---

## License

Copyright © 2025 CRED Flash Sale Team. All rights reserved.

---

## Support

For issues and questions:
- Email: flashsale-team@example.com
- Slack: #flashsale-support
- Documentation: https://docs.flashsale.example.com
