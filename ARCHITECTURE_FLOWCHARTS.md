# Flash Sale Application - Architecture & Flow Charts

This document provides comprehensive flowcharts for understanding the Flash Sale application architecture, request flows, and component interactions.

## Table of Contents
1. [High-Level Architecture](#high-level-architecture)
2. [Reservation Flow](#reservation-flow)
3. [Checkout Flow](#checkout-flow)
4. [Inventory Management Flow](#inventory-management-flow)
5. [Caching Strategy](#caching-strategy)
6. [Event-Driven Architecture](#event-driven-architecture)
7. [Database Schema](#database-schema)
8. [Component Interaction](#component-interaction)

---

## High-Level Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        A[Mobile/Web Client]
    end

    subgraph "API Gateway"
        B[Load Balancer]
    end

    subgraph "Application Layer"
        C[Spring Boot Application]
        D[Controllers]
        E[Services]
        F[Repositories]
    end

    subgraph "Caching Layer"
        G[Redis Cache]
        H[RedisCacheService]
    end

    subgraph "Database Layer"
        I[(PostgreSQL)]
    end

    subgraph "Messaging Layer"
        J[Kafka Producer]
        K[Kafka Topics]
    end

    subgraph "Monitoring"
        L[CloudWatch Metrics]
        M[Application Logs]
    end

    A -->|HTTP/HTTPS| B
    B -->|Route Request| C
    C --> D
    D --> E
    E --> H
    E --> F
    E --> J
    H <-->|Cache Operations| G
    F <-->|CRUD Operations| I
    J -->|Publish Events| K
    E -->|Send Metrics| L
    C -->|Log Events| M

    style A fill:#e1f5ff
    style G fill:#fff4e1
    style I fill:#e8f5e9
    style K fill:#f3e5f5
    style L fill:#fce4ec
```

---

## Reservation Flow

### Complete Reservation Process

```mermaid
sequenceDiagram
    participant Client
    participant Controller
    participant ReservationService
    participant InventoryService
    participant RedisCache
    participant Database
    participant KafkaProducer
    participant Metrics

    Client->>Controller: POST /api/v1/reservations
    activate Controller

    Controller->>ReservationService: createReservation(userId, skuId, quantity)
    activate ReservationService

    %% Step 1: Check User Purchase Limit
    ReservationService->>RedisCache: hasUserPurchased(userId, skuId)
    RedisCache-->>ReservationService: false

    %% Step 2: Check for Existing Reservation
    ReservationService->>Database: findByUserIdAndSkuIdAndStatus(RESERVED)
    Database-->>ReservationService: empty

    %% Step 3: Reserve Inventory
    ReservationService->>InventoryService: reserveInventory(skuId, quantity)
    activate InventoryService

    InventoryService->>RedisCache: decrementStockCount(skuId, quantity)
    RedisCache-->>InventoryService: newCount (optimistic)

    InventoryService->>Database: UPDATE inventory SET reserved_count++
    Note over Database: Pessimistic Lock: SELECT FOR UPDATE
    Database-->>InventoryService: Inventory updated

    InventoryService-->>ReservationService: Inventory Reserved
    deactivate InventoryService

    %% Step 4: Create Reservation Record
    ReservationService->>Database: INSERT INTO reservations
    Database-->>ReservationService: Reservation created

    %% Step 5: Cache Active Reservation
    ReservationService->>RedisCache: cacheActiveReservation(userId, skuId, reservationId)

    %% Step 6: Publish Kafka Event
    ReservationService->>KafkaProducer: publishReservationCreated(event)
    KafkaProducer->>Metrics: recordReservationCreated()

    ReservationService-->>Controller: Reservation DTO
    deactivate ReservationService

    Controller-->>Client: 201 Created
    deactivate Controller

    %% Async: Event Processing
    Note over KafkaProducer: Kafka Topic: flash-sale-reservations
    Note over KafkaProducer: Partition Key: skuId (single-writer pattern)
```

### Reservation Conflict Scenarios

```mermaid
flowchart TD
    A[Start: Create Reservation Request] --> B{Check User Purchase Limit}
    B -->|Already Purchased| C[Return 409: Already Purchased]
    B -->|Not Purchased| D{Check Existing Reservation}

    D -->|Has Active Reservation| E[Return 409: Already Reserved]
    D -->|No Active Reservation| F{Check Inventory Availability}

    F -->|Out of Stock| G[Return 400: Out of Stock]
    F -->|Available| H[Reserve Inventory with Lock]

    H --> I{Lock Acquired?}
    I -->|No - Optimistic Lock Failed| J[Retry or Return 409]
    I -->|Yes| K[Create Reservation Record]

    K --> L[Cache Active Reservation]
    L --> M[Publish Kafka Event]
    M --> N[Return 201: Success]

    style C fill:#ffcdd2
    style E fill:#ffcdd2
    style G fill:#ffcdd2
    style J fill:#fff9c4
    style N fill:#c8e6c9
```

---

## Checkout Flow

### Order Checkout Process

```mermaid
sequenceDiagram
    participant Client
    participant OrderController
    participant OrderService
    participant ReservationService
    participant InventoryService
    participant Database
    participant KafkaProducer

    Client->>OrderController: POST /api/v1/orders/checkout
    activate OrderController

    OrderController->>OrderService: createOrder(checkoutRequest)
    activate OrderService

    %% Step 1: Validate Reservation
    OrderService->>Database: findReservationById(reservationId)
    Database-->>OrderService: Reservation

    OrderService->>OrderService: validateReservation()
    Note over OrderService: Check: Status = RESERVED<br/>Check: Not Expired<br/>Check: No Existing Order

    %% Step 2: Validate Payment
    OrderService->>OrderService: validatePayment()
    Note over OrderService: Verify: Payment Method<br/>Verify: Transaction ID<br/>Simulate Payment Gateway

    %% Step 3: Create Order
    OrderService->>Database: INSERT INTO orders
    Database-->>OrderService: Order created

    %% Step 4: Confirm Reservation
    OrderService->>ReservationService: confirmReservation(reservationId)
    activate ReservationService

    ReservationService->>Database: UPDATE reservations SET status=CONFIRMED
    ReservationService->>InventoryService: convertReservedToSold(skuId, quantity)

    activate InventoryService
    InventoryService->>Database: UPDATE inventory<br/>SET sold_count++, reserved_count--
    InventoryService-->>ReservationService: Converted
    deactivate InventoryService

    ReservationService->>KafkaProducer: publishReservationConfirmed(event)
    ReservationService-->>OrderService: Confirmed
    deactivate ReservationService

    %% Step 5: Mark User as Purchased
    OrderService->>Database: Mark purchase in cache/DB

    %% Step 6: Publish Order Event
    OrderService->>KafkaProducer: publishOrderCreated(orderId, userId, skuId)

    OrderService-->>OrderController: Order DTO
    deactivate OrderService

    OrderController-->>Client: 201 Created
    deactivate OrderController
```

### Order State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: Create Order

    PENDING --> CONFIRMED: Payment Successful
    PENDING --> FAILED: Payment Failed
    PENDING --> CANCELLED: User Cancellation

    CONFIRMED --> FULFILLED: Order Shipped
    CONFIRMED --> CANCELLED: Cancellation Request

    FULFILLED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]

    note right of CONFIRMED
        Inventory: reserved → sold
        Reservation: CONFIRMED
    end note

    note right of CANCELLED
        Inventory: reserved → available
        Reservation: CANCELLED
    end note
```

---

## Inventory Management Flow

### Inventory Update Flow

```mermaid
flowchart TD
    A[Inventory Operation Requested] --> B{Operation Type}

    B -->|Reserve| C[Reserve Inventory]
    B -->|Confirm| D[Convert Reserved to Sold]
    B -->|Release| E[Release Reserved Inventory]
    B -->|Cancel Order| F[Return Sold to Available]

    C --> C1[Decrement Redis Cache]
    C1 --> C2[Acquire Pessimistic Lock]
    C2 --> C3[UPDATE: available_count--<br/>reserved_count++]
    C3 --> C4[Release Lock]
    C4 --> C5[Publish Inventory Update Event]

    D --> D1[UPDATE: reserved_count--<br/>sold_count++]
    D1 --> D2[Invalidate Redis Cache]
    D2 --> D3[Publish Inventory Update Event]

    E --> E1[Increment Redis Cache]
    E1 --> E2[UPDATE: reserved_count--<br/>available_count++]
    E2 --> E3[Publish Inventory Update Event]

    F --> F1[UPDATE: sold_count--<br/>available_count++]
    F1 --> F2[Invalidate Redis Cache]
    F2 --> F3[Publish Inventory Update Event]

    C5 --> G[End]
    D3 --> G
    E3 --> G
    F3 --> G

    style C fill:#e3f2fd
    style D fill:#e8f5e9
    style E fill:#fff3e0
    style F fill:#fce4ec
```

### Pessimistic Locking Strategy

```mermaid
sequenceDiagram
    participant T1 as Transaction 1
    participant T2 as Transaction 2
    participant DB as Database

    Note over T1,DB: Multiple users trying to reserve same product

    T1->>DB: BEGIN TRANSACTION
    T1->>DB: SELECT * FROM inventory<br/>WHERE sku_id = 'SKU-001'<br/>FOR UPDATE
    DB-->>T1: Lock Acquired (Row Locked)

    par Transaction 2 Waits
        T2->>DB: BEGIN TRANSACTION
        T2->>DB: SELECT * FROM inventory<br/>WHERE sku_id = 'SKU-001'<br/>FOR UPDATE
        Note over T2,DB: T2 Waits for Lock...
    end

    T1->>DB: UPDATE inventory<br/>SET reserved_count = reserved_count + 1<br/>WHERE sku_id = 'SKU-001'
    T1->>DB: COMMIT
    DB-->>T1: Success
    Note over DB: Lock Released

    DB-->>T2: Lock Acquired
    T2->>DB: UPDATE inventory<br/>SET reserved_count = reserved_count + 1<br/>WHERE sku_id = 'SKU-001'
    T2->>DB: COMMIT
    DB-->>T2: Success
```

---

## Caching Strategy

### Redis Cache Flow

```mermaid
flowchart TD
    A[Request: Get Product Availability] --> B{Check Redis Cache}

    B -->|Cache Hit| C[Return Cached Data]
    B -->|Cache Miss| D[Query Database]

    D --> E[Retrieve from PostgreSQL]
    E --> F[Store in Redis Cache]
    F --> G[Set TTL: 5 minutes]
    G --> H[Return Data to Client]

    C --> I[Check TTL]
    I -->|Valid| J[Return to Client]
    I -->|Expired| D

    K[Inventory Update Event] --> L[Invalidate Cache]
    L --> M[Delete Redis Key: stock:skuId]

    N[Reservation Created] --> O[Update Cache]
    O --> P[Decrement: stock:skuId]

    style B fill:#fff3e0
    style C fill:#c8e6c9
    style D fill:#e3f2fd
    style L fill:#ffcdd2
```

### Cache Keys and TTL

```mermaid
graph LR
    subgraph "Redis Cache Keys"
        A[stock:SKU_ID<br/>TTL: 5 min<br/>Type: Integer]
        B[product:SKU_ID<br/>TTL: 10 min<br/>Type: JSON]
        C[user_limit:USER_ID:SKU_ID<br/>TTL: 24 hours<br/>Type: Boolean]
        D[reservation:USER_ID:SKU_ID<br/>TTL: 3 min<br/>Type: String]
    end

    subgraph "Operations"
        E[Atomic DECR]
        F[Atomic INCR]
        G[GET/SET]
        H[DELETE]
    end

    A --> E
    A --> F
    B --> G
    C --> G
    D --> G
    A --> H
    B --> H

    style A fill:#e3f2fd
    style B fill:#e8f5e9
    style C fill:#fff3e0
    style D fill:#f3e5f5
```

---

## Event-Driven Architecture

### Kafka Event Flow

```mermaid
graph TB
    subgraph "Event Producers"
        A[ReservationService]
        B[OrderService]
        C[InventoryService]
    end

    subgraph "Kafka Topics"
        D[flash-sale-reservations<br/>Partitions: 10<br/>Key: skuId]
        E[flash-sale-inventory-updates<br/>Partitions: 10<br/>Key: skuId]
        F[flash-sale-orders<br/>Partitions: 5<br/>Key: skuId]
    end

    subgraph "Event Consumers (Future)"
        G[Analytics Service]
        H[Notification Service]
        I[Inventory Sync Service]
        J[Fraud Detection]
    end

    A -->|CREATED<br/>CONFIRMED<br/>EXPIRED<br/>CANCELLED| D
    B -->|ORDER_CREATED<br/>ORDER_FULFILLED| F
    C -->|RESERVED<br/>RELEASED<br/>SOLD| E

    D --> G
    D --> H
    E --> I
    F --> G
    F --> J

    style D fill:#e3f2fd
    style E fill:#e8f5e9
    style F fill:#fff3e0
```

### Single-Writer Pattern

```mermaid
sequenceDiagram
    participant P1 as Producer
    participant K as Kafka
    participant C1 as Consumer 1<br/>(Partition 0)
    participant C2 as Consumer 2<br/>(Partition 1)

    Note over P1,K: All events for SKU-001 go to same partition

    P1->>K: Event 1: SKU-001<br/>Hash(SKU-001) % 10 = 3
    K->>C1: Route to Partition 3

    P1->>K: Event 2: SKU-001<br/>Hash(SKU-001) % 10 = 3
    K->>C1: Route to Partition 3

    P1->>K: Event 3: SKU-002<br/>Hash(SKU-002) % 10 = 7
    K->>C2: Route to Partition 7

    Note over C1: Sequential Processing<br/>Ensures Order
    Note over C2: Parallel Processing<br/>Different SKUs
```

---

## Database Schema

### Entity Relationship Diagram

```mermaid
erDiagram
    PRODUCTS ||--o{ INVENTORY : has
    PRODUCTS ||--o{ RESERVATIONS : "reserved for"
    RESERVATIONS ||--o| ORDERS : "confirmed by"

    PRODUCTS {
        varchar sku_id PK
        varchar name
        decimal base_price
        decimal flash_sale_price
        int total_inventory
        boolean is_active
        varchar category
        timestamp created_at
    }

    INVENTORY {
        uuid inventory_id PK
        varchar sku_id FK
        int total_count
        int reserved_count
        int sold_count
        int available_count
        int version
        timestamp updated_at
    }

    RESERVATIONS {
        varchar reservation_id PK
        varchar user_id
        varchar sku_id FK
        int quantity
        enum status
        timestamp created_at
        timestamp expires_at
        timestamp confirmed_at
    }

    ORDERS {
        varchar order_id PK
        varchar reservation_id FK
        varchar user_id
        varchar sku_id FK
        int quantity
        decimal total_price
        enum status
        varchar payment_method
        timestamp created_at
    }
```

### Inventory State Calculation

```
┌─────────────────────────────────────────────────────┐
│            Inventory State                          │
├─────────────────────────────────────────────────────┤
│                                                     │
│  total_count = 100                                  │
│  ├─ reserved_count = 20  (2-min reservation)      │
│  ├─ sold_count = 30      (completed purchases)    │
│  └─ available_count = 50  (can be reserved)       │
│                                                     │
│  Formula:                                           │
│  available_count = total_count - reserved - sold   │
│                                                     │
│  ┌───────────────────────────────────────────┐    │
│  │  Optimistic Locking (JPA @Version)       │    │
│  │  - Prevents race conditions              │    │
│  │  - Retry on OptimisticLockException      │    │
│  └───────────────────────────────────────────┘    │
│                                                     │
│  ┌───────────────────────────────────────────┐    │
│  │  Pessimistic Locking (SELECT FOR UPDATE) │    │
│  │  - Critical section protection           │    │
│  │  - Ensures serial access per SKU         │    │
│  └───────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

---

## Component Interaction

### Layered Architecture

```mermaid
graph TB
    subgraph "Presentation Layer"
        A1[ReservationController]
        A2[ProductController]
        A3[OrderController]
    end

    subgraph "Service Layer"
        B1[ReservationService]
        B2[ProductService]
        B3[OrderService]
        B4[InventoryService]
    end

    subgraph "Repository Layer"
        C1[ReservationRepository]
        C2[ProductRepository]
        C3[OrderRepository]
        C4[InventoryRepository]
    end

    subgraph "Infrastructure Layer"
        D1[RedisCacheService]
        D2[KafkaProducerService]
        D3[CloudWatchMetrics]
    end

    subgraph "Domain Layer"
        E1[Reservation Entity]
        E2[Product Entity]
        E3[Order Entity]
        E4[Inventory Entity]
    end

    A1 --> B1
    A2 --> B2
    A3 --> B3

    B1 --> C1
    B1 --> B4
    B2 --> C2
    B2 --> B4
    B3 --> C3
    B3 --> B1
    B4 --> C4

    B1 --> D1
    B2 --> D1
    B1 --> D2
    B3 --> D2
    B1 --> D3
    B2 --> D3
    B3 --> D3

    C1 --> E1
    C2 --> E2
    C3 --> E3
    C4 --> E4

    style A1 fill:#e3f2fd
    style A2 fill:#e3f2fd
    style A3 fill:#e3f2fd
    style B1 fill:#e8f5e9
    style B2 fill:#e8f5e9
    style B3 fill:#e8f5e9
    style B4 fill:#e8f5e9
    style D1 fill:#fff3e0
    style D2 fill:#f3e5f5
    style D3 fill:#fce4ec
```

### Request Processing Flow

```
┌──────────────────────────────────────────────────────────────┐
│                     HTTP Request Flow                        │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │   DispatcherServlet     │
              └─────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │   @RestController       │
              │   - Request Validation  │
              │   - @RequestBody bind   │
              └─────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │   @Service Layer        │
              │   - Business Logic      │
              │   - Transaction Mgmt    │
              │   - Cache Operations    │
              └─────────────────────────┘
                            │
                    ┌───────┴───────┐
                    ▼               ▼
        ┌──────────────────┐  ┌──────────────┐
        │  @Repository     │  │ Redis Cache  │
        │  - JPA Queries   │  │ - GET/SET    │
        │  - Transactions  │  │ - INCR/DECR  │
        └──────────────────┘  └──────────────┘
                    │               │
                    ▼               ▼
        ┌──────────────────┐  ┌──────────────┐
        │   PostgreSQL     │  │   Redis      │
        │   - ACID         │  │   - Fast     │
        │   - Locking      │  │   - Atomic   │
        └──────────────────┘  └──────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │   Kafka Producer        │
              │   - Async Events        │
              │   - Partitioning        │
              └─────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │   Response DTO          │
              │   - JSON Serialization  │
              │   - HTTP Status Code    │
              └─────────────────────────┘
```

---

## Error Handling Flow

```mermaid
flowchart TD
    A[Request Received] --> B{Validation}
    B -->|Invalid| C[400 Bad Request]
    B -->|Valid| D{Business Logic}

    D -->|Stock Unavailable| E[400 Bad Request<br/>OUT_OF_STOCK]
    D -->|Already Reserved| F[409 Conflict<br/>ALREADY_RESERVED]
    D -->|Already Purchased| G[409 Conflict<br/>ALREADY_PURCHASED]
    D -->|Not Found| H[404 Not Found]
    D -->|Database Error| I[500 Internal Error]
    D -->|Success| J[200/201 Success]

    I --> K[GlobalExceptionHandler]
    K --> L[Log Error]
    L --> M[Return Error Response DTO]

    style C fill:#ffcdd2
    style E fill:#ffcdd2
    style F fill:#fff9c4
    style G fill:#fff9c4
    style H fill:#ffecb3
    style I fill:#ffcdd2
    style J fill:#c8e6c9
```

---

## Performance Optimization

### Multi-Level Caching Strategy

```
┌──────────────────────────────────────────────────────┐
│           Read Request Performance Path              │
└──────────────────────────────────────────────────────┘

Request: Get Product Availability (SKU-001)
    │
    ▼
┌────────────────┐
│  L1: Redis     │  ◄── Hit: ~1-5ms
│  stock:SKU-001 │
└────────────────┘
    │ Miss
    ▼
┌────────────────┐
│  L2: Database  │  ◄── Hit: ~10-50ms
│  SELECT *      │      (with connection pool)
│  FROM inventory│
└────────────────┘
    │
    ▼
┌────────────────┐
│  Cache Result  │  ◄── Write-back to Redis
│  in Redis      │      TTL: 5 minutes
└────────────────┘
    │
    ▼
Return to Client

═══════════════════════════════════════════════════════
Write Request Performance Path
═══════════════════════════════════════════════════════

Request: Create Reservation
    │
    ▼
┌────────────────┐
│ Optimistic:    │  ◄── Fast path
│ Decr Redis     │      ~1-5ms
└────────────────┘
    │
    ▼
┌────────────────┐
│ Pessimistic:   │  ◄── Acquire lock
│ DB Lock        │      ~10-100ms
│ SELECT FOR     │      (under contention)
│ UPDATE         │
└────────────────┘
    │
    ▼
┌────────────────┐
│ Update DB      │  ◄── ACID transaction
│ Commit         │      ~5-20ms
└────────────────┘
    │
    ▼
┌────────────────┐
│ Publish Event  │  ◄── Async, non-blocking
│ to Kafka       │      ~1-5ms
└────────────────┘

Total Latency (P95): ~50-150ms
```

---

## Monitoring & Observability

```mermaid
graph TB
    subgraph "Application"
        A[Spring Boot App]
    end

    subgraph "Metrics"
        B[Micrometer]
        C[CloudWatch Metrics]
        D[JVM Metrics]
    end

    subgraph "Logging"
        E[SLF4J/Logback]
        F[Application Logs]
        G[CloudWatch Logs]
    end

    subgraph "Health Checks"
        H[/actuator/health]
        I[Database Health]
        J[Redis Health]
        K[Kafka Health]
    end

    subgraph "Alerting"
        L[High Error Rate Alert]
        M[High Latency Alert]
        N[Low Inventory Alert]
    end

    A --> B
    B --> C
    A --> D
    A --> E
    E --> F
    F --> G
    A --> H
    H --> I
    H --> J
    H --> K
    C --> L
    C --> M
    A --> N

    style C fill:#fce4ec
    style G fill:#fce4ec
    style L fill:#ffcdd2
    style M fill:#ffcdd2
    style N fill:#ffcdd2
```

---

## Key Design Patterns Used

| Pattern | Implementation | Purpose |
|---------|---------------|---------|
| **Repository Pattern** | JPA Repositories | Data access abstraction |
| **Service Layer Pattern** | @Service classes | Business logic encapsulation |
| **DTO Pattern** | Request/Response DTOs | API contract separation |
| **Builder Pattern** | Lombok @Builder | Object construction |
| **Single-Writer Pattern** | Kafka partitioning by SKU | Inventory consistency |
| **Cache-Aside Pattern** | Redis caching | Performance optimization |
| **Optimistic Locking** | JPA @Version | Concurrency control (reads) |
| **Pessimistic Locking** | SELECT FOR UPDATE | Concurrency control (writes) |
| **Event Sourcing** | Kafka events | Audit trail & async processing |
| **Circuit Breaker** | (Future) Resilience4j | Fault tolerance |

---

## Scalability Considerations

```
┌──────────────────────────────────────────────────────┐
│              Horizontal Scaling                      │
└──────────────────────────────────────────────────────┘

         Load Balancer
              │
    ┌─────────┼─────────┐
    │         │         │
  App 1     App 2     App 3    ◄── Stateless instances
    │         │         │
    └─────────┼─────────┘
              │
    ┌─────────┴─────────┐
    │                   │
  Redis            PostgreSQL
 (Cluster)        (Read Replicas)
    │                   │
    │                   │
  Kafka            Kafka
(Partitioned)    (Replicated)

Key Points:
• Stateless application design
• Redis for shared session/cache
• Database read replicas
• Kafka partitioning for parallel processing
• Pessimistic locking prevents overselling
```

---

This documentation provides comprehensive flowcharts and diagrams to understand the Flash Sale application architecture, data flows, and component interactions.
