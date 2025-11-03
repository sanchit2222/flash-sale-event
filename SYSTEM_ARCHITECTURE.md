# Flash Sale System - Detailed Architecture Design

## Table of Contents
1. [System Overview](#system-overview)
2. [Architectural Decisions](#architectural-decisions)
3. [Data Flow Architecture](#data-flow-architecture)
4. [Deployment Strategy](#deployment-strategy)

---

## System Overview

### Context
A Flash Sale System handling extreme traffic spikes with millions of concurrent users competing for 10,000 units within minutes. Must guarantee zero oversell, maintain sub-150ms latency, and provide complete auditability.

### Key Requirements Summary
- **Peak Load**: 250k RPS reads, 25k RPS writes to single SKU
- **Latency SLOs**: Search P95 ≤150ms, Reserve P95 ≤120ms, Checkout P95 ≤450ms
- **Critical Constraint**: Zero oversell (hard guarantee)
- **Fairness**: Prevent bot abuse, ensure equitable distribution
- **Auditability**: Immutable logs for all transactions

---

## Architectural Decisions

### Decision 1: Load Balancing & API Gateway Strategy

**Selected Solution**: Multi-tier load balancing with dedicated API Gateway
```
CDN/Global Load Balancer → Regional Load Balancers → API Gateway (Rate Limit, Auth) → Services
```

**Rationale**:
- Distributes the 250k RPS read traffic across multiple regions before hitting the API Gateway
- API Gateway provides centralized rate limiting, authentication, and request validation
- Reduces load on individual service instances
- Enables geographic distribution and DDoS protection
- Separates concerns: routing, rate limiting, and authentication at edge vs. business logic

**Alternative Options**:

**Option A: Single API Gateway (no CDN/regional load balancing)**
- Pros:
  - Simpler architecture, fewer components to manage
  - Lower operational overhead
  - Easier to implement initially
- Cons:
  - Single point of failure for global traffic
  - Cannot handle 250k RPS at single node
  - High latency for geographically distant users
  - Insufficient for DDoS mitigation
- When it would be better: Low traffic systems (<10k RPS), single region deployments

**Option B: Pure CDN with origin caching (no dedicated API Gateway)**
- Pros:
  - Very low latency for read traffic (all served from CDN edge)
  - Excellent for static/cacheable content
  - High throughput for read operations
- Cons:
  - Cannot cache write operations (reserves, checkouts)
  - Difficult to implement custom rate limiting logic
  - Hard to enforce per-user limits at edge
  - Cache invalidation complexity
- When it would be better: Read-heavy systems (>95% reads), public APIs with minimal writes

**Option C: Service Mesh Gateway (Istio/Envoy)**
- Pros:
  - Advanced traffic management (canary deployments, circuit breakers)
  - Built-in observability (distributed tracing)
  - Service-to-service authentication
  - Dynamic routing based on health
- Cons:
  - Significant operational complexity
  - Resource overhead (additional sidecars per pod)
  - Steep learning curve for team
  - Latency overhead from sidecars (adds 5-10ms per hop)
- When it would be better: Large microservices ecosystems (>20 services) with complex routing

**Tradeoff Analysis**:
- **Selected approach gains**: Simplicity, performance, proven scalability
- **Selected approach sacrifices**: Some advanced traffic management features available in service mesh
- **Why acceptable**: For this system, basic load balancing + rate limiting at API Gateway is sufficient. Advanced traffic management features (canary deployments, etc.) can be handled via CI/CD pipeline

**Technology Recommendation**:
- Global: CloudFlare/AWS CloudFront (CDN)
- Regional: AWS Network Load Balancer or HAProxy
- API Gateway: AWS API Gateway, Kong, or Spring Cloud Gateway

---

### Decision 2: Inventory Management & Distributed Locking Strategy

**Selected Solution**: Single-Writer Pattern with Redis-backed Distributed Lock
```
All reservation requests → Message Queue → Single Serialized Consumer → Atomic DB transaction
```

**Rationale**:
- 25k RPS writes to single SKU creates massive contention for inventory lock
- Single-writer pattern guarantees zero race conditions and zero oversell
- Message queue provides fairness (FIFO processing)
- Serialization ensures atomicity of stock decrement + reservation creation
- Redis lock is fast (sub-millisecond) and reliable
- Decouples request acceptance from processing, enabling fair queuing

**Alternative Options**:

**Option A: Optimistic Locking with Version Numbers**
```sql
UPDATE inventory SET version = version + 1, reserved_count = reserved_count + 1
WHERE sku_id = ? AND version = ?
```
- Pros:
  - No explicit locks needed
  - Better concurrency under low contention
  - Simpler implementation
  - Lower latency for each attempt
- Cons:
  - High retry rates under high contention (25k RPS to single SKU)
  - Retry storm can amplify load on database
  - Unpredictable latency (clients retry 10-100 times)
  - Wasted database cycles on failed updates
  - Cannot provide user fairness (fast retries win)
- When it would be better: Multiple SKUs with balanced load, lower RPS

**Option B: Pessimistic Locking with Row Locks**
```sql
SELECT * FROM inventory WHERE sku_id = ? FOR UPDATE
-- User check and stock decrement
UPDATE inventory SET reserved_count = reserved_count + 1 WHERE sku_id = ?
```
- Pros:
  - Guaranteed no race conditions
  - Immediate consistency
  - Standard SQL approach
- Cons:
  - Database lock contention kills throughput at 25k RPS
  - High latency (50-100ms+ due to lock waits)
  - Blocks all concurrent requests
  - Deadlock risk with multiple tables
  - Cannot scale horizontally (single database bottleneck)
- When it would be better: Single-node systems, lower load (<1k RPS)

**Option C: Event Sourcing with Consensus (Raft/Paxos)**
- Pros:
  - Distributed consensus ensures consistency
  - Replicated state across nodes
  - Can handle multi-region scenarios
  - Auditable event log inherently
- Cons:
  - Significant operational complexity
  - Latency overhead from consensus rounds (50-200ms)
  - Requires specialized knowledge
  - Overkill for single region deployment
- When it would be better: Multi-region systems with cross-region consistency requirements

**Option D: In-Memory Atomics with Periodic Persistence**
- Pros:
  - Extremely fast (microseconds)
  - No database lock contention
  - Simple to implement
- Cons:
  - Data loss on node failure
  - Hard to maintain consistency across replicas
  - Cannot recover incomplete transactions
  - Violates durability requirement
- When it would be better: Never acceptable for this use case (financial transactions)

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Zero oversell guarantee
  - Fair FIFO processing (all users treated equally)
  - Predictable latency (no retry storms)
  - Horizontally scalable queuing
- **Selected approach sacrifices**:
  - Per-request latency slightly higher than optimistic locking (message queueing adds 10-20ms)
  - Additional infrastructure (message queue)
  - Slightly more complex implementation
- **Why acceptable**: The latency sacrifice is acceptable because:
  1. Still meets P95 ≤120ms target for reservations
  2. Fair queuing is a hard requirement (prevent bot wins)
  3. Zero oversell is non-negotiable
  4. Predictable latency is better than unpredictable retries

**Technology Recommendation**:
- Message Queue: Apache Kafka (exactly-once semantics, partitionable by SKU)
- Distributed Lock: Redis with SET NX EX (simple, fast, <1ms)
- Consumer: Single-threaded Java service with persistence

---

### Decision 3: Cache Layer Architecture

**Selected Solution**: Multi-level caching with Redis Cluster + CDN
```
Client → CDN (static assets) → Service Cache (Redis Cluster) → Database
```

**Rationale**:
- 250k RPS reads, 95% are cacheable (product availability views)
- CDN handles static assets and product metadata
- Redis Cluster provides distributed cache for dynamic data (stock counts)
- Cache invalidation on every reservation ensures consistency
- Separates concerns: static content (CDN) vs. dynamic content (Redis)
- Enables horizontal scaling without database bottleneck

**Alternative Options**:

**Option A: Single Redis Instance**
- Pros:
  - Simpler deployment
  - Lower operational overhead
  - Single point of consistency
  - Easier to reason about cache state
- Cons:
  - Single point of failure
  - Cannot handle 250k RPS to single node
  - No high availability
  - Data loss if node fails
- When it would be better: Non-critical systems, <10k RPS

**Option B: Redis Replication (Master-Slave with Failover)**
- Pros:
  - HA via failover
  - Read scalability (reads from replicas)
  - Better than single instance
- Cons:
  - Slave replication lag causes stale reads
  - Master is still bottleneck for writes (cache invalidations)
  - Failover introduces brief downtime
  - Not suitable for 250k RPS
- When it would be better: Systems with <50k RPS, acceptable eventual consistency for cache

**Option C: Redis Cluster (Sharded)**
- Pros:
  - Horizontal scalability across shards
  - No single bottleneck
  - Built-in replication within cluster
  - Automatic failover per shard
  - Can handle 250k RPS across nodes
- Cons:
  - More complex configuration and management
  - Client library must support cluster protocol
  - Multi-key operations cross shards (slower)
  - Rebalancing can be disruptive
- When it would be better: High-throughput systems (>50k RPS), long-term deployments

**Option D: In-Memory Cache on Each Service**
- Pros:
  - No network latency (microseconds)
  - No external dependency
  - Simplest implementation
- Cons:
  - Inconsistent cache across instances (different versions)
  - Hard to invalidate (broadcast to all nodes)
  - Memory waste (replicated on each instance)
  - Difficult to scale (cache misses on new instances)
- When it would be better: Single-instance systems, non-critical data

**Option E: Memcached**
- Pros:
  - Simpler protocol than Redis
  - Slightly lower latency
  - Good for read-heavy workloads
- Cons:
  - No persistence
  - Limited data types (strings only)
  - No pub/sub for cache invalidation
  - Single point of failure for cached data
- When it would be better: Read-only caches, non-critical data

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Handles 250k RPS reads without database load
  - Automatic failover and high availability
  - Scalable to future load increases
  - Strong consistency via intelligent invalidation
- **Selected approach sacrifices**:
  - Operational complexity (cluster management, monitoring)
  - Network latency vs. in-process cache (~1-5ms vs. microseconds)
  - Cost of Redis Cluster infrastructure
- **Why acceptable**: The consistency and scalability gains outweigh latency cost since:
  1. 1-5ms latency is negligible vs. 150ms P95 target
  2. In-memory caches cause consistency problems in distributed systems
  3. Redis Cluster fits natural sharding by SKU

**Technology Recommendation**: Redis Cluster 7.0+ with:
- Persistence: RDB snapshots every 10 seconds
- Backup: Multi-region replicas with hourly sync
- Client: Jedis or Lettuce with cluster support

---

### Decision 4: Database Consistency Model

**Selected Solution**: PostgreSQL with Strong Consistency for Transactional Data + Eventual Consistency Read Replicas
```
Writes → PostgreSQL Primary (strong consistency)
Reads (transactional) → Primary
Reads (analytics) → Read Replicas (eventual consistency)
```

**Rationale**:
- Inventory, reservations, and orders MUST have strong consistency (zero oversell)
- Analytics and reporting can tolerate eventual consistency (asynchronous replication)
- PostgreSQL provides ACID guarantees, proven reliability for financial systems
- Read replicas scale read-heavy analytics without impacting transactional writes
- Separates transactional and analytical workloads

**Alternative Options**:

**Option A: Eventual Consistency Everywhere (NoSQL like Cassandra)**
- Pros:
  - Horizontal scalability across nodes
  - No single primary bottleneck
  - High availability (any node can accept writes)
  - Faster writes (no coordination)
- Cons:
  - **Cannot guarantee zero oversell** (concurrent writes create race conditions)
  - Reconciliation complexity for conflicts
  - Complex application logic to handle conflicts
  - Unsuitable for financial systems
- When it would be better: Never for inventory systems (this requirement is non-negotiable)

**Option B: Strong Consistency Everywhere (Multi-region replication)**
- Pros:
  - Guarantees consistency globally
  - Audit trail everywhere
  - Can serve from nearest region
- Cons:
  - Synchronous replication adds latency (cross-region round trips)
  - Violates P95 ≤120ms latency SLO
  - Complex conflict resolution for network partitions
  - CAP theorem: must choose between consistency and availability
- When it would be better: When multi-region failover with consistency is critical (not our case)

**Option C: CQRS Pattern (Separate Read/Write Databases)**
- Pros:
  - Optimizes write model and read model independently
  - Read model can use NoSQL (better scaling)
  - Allows different schemas for reads vs. writes
  - Flexible projection strategies
- Cons:
  - Significant architectural complexity
  - Eventual consistency between models (stale reads)
  - Operational overhead (two databases to manage)
  - Difficult to debug cross-database issues
- When it would be better: Complex systems with vastly different read/write patterns (not our case)

**Option D: Sharded PostgreSQL (Multiple databases by SKU)**
- Pros:
  - Horizontal write scalability (each shard handles portion of SKUs)
  - Independent scaling per shard
  - Better lock contention (no single inventory table bottleneck)
- Cons:
  - Complex shard key management
  - Cross-shard queries become distributed transactions
  - Difficult to rebalance shards
  - Operational overhead multiplied
- When it would be better: Multi-product systems with hundreds of concurrent SKUs (we have 1 SKU per event)

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Zero oversell guarantee (ACID transactions)
  - Familiar technology (PostgreSQL proven in production)
  - Clean separation of transactional vs. analytical concerns
  - Proven scalability for our load with proper indexing
- **Selected approach sacrifices**:
  - Primary database is write bottleneck (mitigated by single-writer pattern)
  - No multi-region failover consistency
  - Analytics slightly stale (replication lag)
- **Why acceptable**:
  1. Zero oversell is non-negotiable requirement
  2. Single-writer pattern removes primary as bottleneck (messages queued, processed serially)
  3. Analytics staleness acceptable per NFR
  4. PostgreSQL proven at scale (Shopify, GitHub, etc.)

**Technology Recommendation**:
- Primary: PostgreSQL 15+ on managed service (AWS RDS)
- Read Replicas: 2-3 regional replicas for analytics
- Backup: Automated daily snapshots, encrypted
- Monitoring: CloudWatch + pgAdmin for query performance

---

### Decision 5: Rate Limiting & Fair-Queuing Algorithm

**Selected Solution**: Token Bucket Algorithm with Fair Queue + Device Fingerprinting
```
Request → Rate Limit Check (Token Bucket) → Fair Queue (FIFO) → Process
```

**Rationale**:
- Token Bucket allows burst traffic (initial wave) while maintaining average rate
- Fair Queue ensures FIFO processing (prevents bot wins)
- Device fingerprinting detects and throttles bot/abuse patterns
- Per-user, per-IP, per-device limits provide defense-in-depth
- Redis atomic operations support efficient token management

**Alternative Options**:

**Option A: Leaky Bucket Algorithm**
```
Requests → Fixed-size bucket → Drain at constant rate
```
- Pros:
  - Extremely predictable output rate
  - Simple to understand
  - Memory efficient
- Cons:
  - Doesn't allow bursts (penalizes legitimate spikes)
  - Users experience delays even with capacity available
  - Not suitable for flash sales (need initial burst to serve legitimate demand)
  - Unfair to late arrivals (queue grows indefinitely)
- When it would be better: Systems requiring constant, predictable flow (CDN origin)

**Option B: Sliding Window Counter**
```
Count requests in last N minutes, reject if > quota
```
- Pros:
  - More accurate than token bucket for small windows
  - Prevents burst at window boundaries
  - Simple implementation
- Cons:
  - Doesn't allow any burst (too restrictive)
  - Requires precise time synchronization
  - Fixed window boundaries cause burst at edges
  - Memory overhead for tracking all requests
- When it would be better: Strict request rate enforcement needed (not our case)

**Option C: Distributed Token Bucket with Centralized Coordination**
- Pros:
  - Precise rate limiting across all nodes
  - No race conditions
  - True fair distribution possible
- Cons:
  - Centralized coordinator is bottleneck (cannot handle 25k RPS)
  - Single point of failure
  - Synchronization latency (network round trip)
  - Defeats purpose of rate limiting
- When it would be better: Never suitable for high-throughput systems

**Option D: No Rate Limiting, Rely on Queue Saturation**
- Pros:
  - Zero latency for rate limiting checks
  - System naturally throttles by queue size
  - Simple
- Cons:
  - Cannot prevent bot attacks (bots overload early)
  - Unfair to legitimate users
  - Queue grows unbounded
  - No SLA for response times
  - OOM risk
- When it would be better: Internal-only systems (never for public API)

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Allows legitimate burst traffic to be processed quickly
  - Fair FIFO queue prevents bots from getting head of line
  - Multi-dimensional limiting (user, IP, device) catches sophisticated attacks
  - Proven algorithm used by major cloud providers
- **Selected approach sacrifices**:
  - Slightly more complex implementation
  - Redis atomic operations overhead (<1ms)
  - False positives possible with device fingerprinting
- **Why acceptable**: Complexity is manageable, performance impact negligible

**Implementation Details**:
```
Tier 1 (Bots/Abusive): 10 requests/minute
Tier 2 (New Users): 50 requests/minute
Tier 3 (Normal Users): 100 requests/minute
Tier 4 (Premium/Verified): 200 requests/minute

Device Fingerprinting detects:
- Same IP × 100+ requests/second → Bot tier
- Same device fingerprint × rapid requests → Bot tier
- VPN/Proxy IPs → Enhanced scrutiny
```

**Technology Recommendation**:
- Implementation: Redis INCRBY with Lua scripts for atomic checks
- Device Fingerprinting: TornadoFx or custom combination of User-Agent, IP, Accept-Language
- Fair Queue: Apache Kafka with single partition per SKU

---

### Decision 6: Idempotency Implementation

**Selected Solution**: Idempotency Keys with Redis Cache + Database Constraints
```
Request with idempotency_key → Check Redis → Check DB → Process → Store Result
```

**Rationale**:
- Idempotency key enables safe retries (prevents double charging)
- Redis cache provides fast lookup for recent requests
- Database unique constraint ensures durability
- Prevents double reservation and double payment
- Required by PCI-DSS for payment processing

**Alternative Options**:

**Option A: Database-Only Unique Constraint**
```sql
ALTER TABLE orders ADD CONSTRAINT unique_idempotency_key UNIQUE(idempotency_key);
```
- Pros:
  - Single source of truth
  - Durable guarantee
  - Simple implementation
- Cons:
  - Database lookup adds latency for every request (~10-20ms)
  - Cannot avoid expensive processing before constraint check
  - Duplicate payment attempts go to payment gateway (rejected, but wasted)
  - Insufficient for 25k RPS writes
- When it would be better: Lower RPS systems (<1k)

**Option B: Redis Only (No Database Constraint)**
- Pros:
  - Fast lookup (<1ms)
  - No database load
  - Sufficient for stateless requests
- Cons:
  - Data loss if Redis crashes (incomplete request data)
  - No durability guarantee
  - Cannot recover after system restart
  - Violates financial transaction requirements
- When it would be better: Non-financial requests, stateless operations

**Option C: Distributed Lock on Idempotency Key**
```
LOCK(idempotency_key) → Check status → Process or return cached
```
- Pros:
  - Prevents concurrent processing of same request
  - Ensures exactly-once semantics
  - Safe for concurrent retries
- Cons:
  - Lock contention if client retries rapidly
  - Complexity in error cases (who releases lock?)
  - Can cause deadlocks
  - Slower than simple lookup
- When it would be better: When concurrent processing must be prevented

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Fast lookup in Redis (usually cache hit)
  - Durable guarantee via database
  - Safe retry semantics
  - PCI-DSS compliant
- **Selected approach sacrifices**:
  - Slightly higher latency for first request (write to both)
  - Requires cleanup of Redis cache over time
  - Two places to maintain idempotency (cache + DB)
- **Why acceptable**:
  1. Financial transactions require durability
  2. Cache hit rate >99% for recent requests
  3. Latency still within SLO

**Implementation Details**:
```
POST /api/v1/reserve
{
  "sku_id": "sku-123",
  "quantity": 1,
  "idempotency_key": "550e8400-e29b-41d4-a716-446655440000"
}

1. Check Redis: GET idempotency:{key} → If exists, return cached response
2. Check DB: SELECT * FROM reservations WHERE idempotency_key = ? → If exists, return from DB
3. Process request (acquire lock, decrement stock, create reservation)
4. Cache result: SET idempotency:{key} {response} EX 86400  (24 hours)
5. Return response

TTL Strategy:
- Keep in Redis for 24 hours (typical retry window)
- Keep in DB indefinitely for audit trail
- Periodic cleanup job: Archive old keys to cold storage
```

**Technology Recommendation**:
- Cache: Redis with 24-hour expiry
- Database: PostgreSQL unique constraint on (user_id, idempotency_key)
- Monitoring: Track duplicate request rate (should be <1% of total)

---

### Decision 7: Distributed Transaction Pattern (Reservation → Payment)

**Selected Solution**: Saga Pattern with Event Sourcing (Choreography-based)
```
Reservation Created Event → Checkout Service listens → Processes Payment → Order Confirmed Event
```

**Rationale**:
- Reservations and payments span multiple services
- Saga pattern avoids distributed 2-phase commit (blocks resources)
- Event sourcing provides complete audit trail
- Choreography-based (event-driven) is simpler than orchestration
- Enables independent scaling of reservation and payment services
- Natural integration with Kafka event stream

**Alternative Options**:

**Option A: Two-Phase Commit (Distributed Transaction)**
```
Coordinator: PREPARE → All services lock resources
Coordinator: COMMIT → All services commit or all ROLLBACK
```
- Pros:
  - ACID guarantees across services
  - Automatic rollback on failure
  - Proven in database systems
- Cons:
  - **Blocks resources during prepare phase** (kills throughput)
  - Coordinator is bottleneck (single point of failure)
  - Network partitions cause indefinite locks
  - Cannot meet P95 ≤120ms latency
  - Not recommended for microservices
- When it would be better: Never for high-throughput systems

**Option B: Saga Pattern with Orchestration (Central Coordinator)**
```
Saga Orchestrator: Reservation → OK? → Call Checkout → OK? → Confirm Order
```
- Pros:
  - Explicit workflow (easier to understand)
  - Centralized error handling
  - Can apply complex business rules
- Cons:
  - Orchestrator is single point of failure
  - Orchestrator becomes bottleneck (25k requests/sec)
  - Complex state machine (error cases, timeouts)
  - Harder to scale (orchestrator coordination)
- When it would be better: Simple workflows with <10 services

**Option C: Event Sourcing without Saga**
- Pros:
  - Complete audit trail
  - Can replay transactions
  - Easy to debug
- Cons:
  - Doesn't solve consistency problem
  - Eventual consistency creates temporary inconsistency
  - Requires complex compensation logic
  - Not suitable if payments fail after reservation
- When it would be better: Analytics-focused systems

**Option D: Request/Response with Retries (No Saga)**
```
POST /reserve → OK → POST /checkout → FAIL
Retry: POST /checkout → OK (now double-paid)
```
- Pros:
  - Simplest implementation
  - No event infrastructure needed
  - Fast (no coordination)
- Cons:
  - **Causes double charging if checkout fails** (violates PCI-DSS)
  - Requires manual intervention to detect
  - Unpredictable user experience
  - Difficult to reconcile
- When it would be better: Non-financial operations

**Tradeoff Analysis**:
- **Selected approach gains**:
  - No blocking resource locks (high throughput)
  - Complete audit trail (event sourcing)
  - Services scale independently
  - Natural integration with event stream
  - Compensation logic explicit (easy to debug)
- **Selected approach sacrifices**:
  - Eventual consistency during saga (brief window)
  - Complex compensation logic for payment failures
  - Requires event sourcing infrastructure (Kafka)
  - More moving parts to operationalize
- **Why acceptable**:
  1. Eventual consistency window is <1 second (acceptable for payment)
  2. Event sourcing provides required auditability
  3. Scales to 25k RPS (2PC cannot)

**Implementation Flow**:
```
User → Reserve API → Creates Reservation Event
        ↓ (Kafka)
        Checkout Service listens
        → Processes Payment
        → Creates Order Confirmed or Order Failed Event
        ↓ (Kafka)
        Notification Service listens
        → Sends confirmation or refund email

Error Handling:
- Payment fails → Order Failed Event → Auto-release reservation after 2 min
- Checkout timeout → Reservation auto-expires after 2 min → Manual refund if needed
```

**Technology Recommendation**:
- Event Stream: Apache Kafka (exactly-once semantics, partitioned by user_id)
- Event Store: PostgreSQL table with immutable events
- Library: Spring Cloud Stream or Axon Framework

---

### Decision 8: Audit Logging Strategy

**Selected Solution**: WORM (Write-Once-Read-Many) Append-Only Tables with Event Sourcing
```
All state changes → Immutable audit_events table → Hash-chained for tamper detection
```

**Rationale**:
- Immutable logs prevent tampering (required for disputes)
- Hash-chaining detects modifications (blockchain-style)
- Audit trail required for reconciliation
- Append-only prevents accidental data loss
- Complies with financial audit requirements

**Alternative Options**:

**Option A: Application Logging (Text Logs)**
- Pros:
  - Simple to implement
  - Works with any language
  - Human readable
- Cons:
  - **Can be easily modified or deleted** (not tamper-proof)
  - Cannot query programmatically
  - Log rotation loses old data
  - Difficult to correlate related events
  - Not suitable for financial disputes
- When it would be better: Non-critical operational logging

**Option B: Syslog/Centralized Log Aggregation (ELK, Splunk)**
- Pros:
  - Centralized collection
  - Full-text search
  - Visualization and alerting
  - Scalable for high volume
- Cons:
  - **Not immutable** (can be deleted or modified)
  - Not designed for financial audit
  - Data loss possible during rebalancing
  - Cannot prove data integrity
  - Retention policies may delete old records
- When it would be better: Operational monitoring, debugging

**Option C: Database Trigger-based Audit**
```sql
CREATE TRIGGER audit_orders_insert AFTER INSERT ON orders
BEGIN
  INSERT INTO orders_audit (old_values, new_values, timestamp) VALUES (NULL, NEW, NOW());
END;
```
- Pros:
  - Automatic, no application changes
  - Durable (same DB as production data)
  - Transactional with original operation
- Cons:
  - Triggers add latency to every write
  - Difficult to include all context (user, IP, reason)
  - Requires trigger maintenance
  - Still in same database (can be altered)
- When it would be better: Simple audit trails in mature systems

**Option D: Blockchain/Distributed Ledger**
- Pros:
  - Cryptographically proven immutability
  - Tamper-evident across network
  - Decentralized verification
- Cons:
  - Extremely high latency (confirmation times)
  - Expensive (computational cost)
  - Overkill for single-organization audit
  - Regulatory uncertainty
  - Cannot integrate with existing systems
- When it would be better: Cross-organizational audits (never for this case)

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Tamper-proof via hash-chaining
  - Immutable (append-only constraint)
  - Queryable (SQL for disputes)
  - Integrates with event sourcing
  - Complete context available (user, IP, device, decision reason)
- **Selected approach sacrifices**:
  - Additional database table (storage cost)
  - Hash computation overhead (<1ms per event)
  - Requires operational discipline (enforce append-only)
- **Why acceptable**:
  1. Storage cost negligible (10,000 events × ~1KB = 10MB)
  2. Computation overhead <1% of total
  3. Immutability critical for dispute resolution

**Data Model**:
```sql
CREATE TABLE audit_events (
  event_id BIGSERIAL PRIMARY KEY,
  event_type VARCHAR(50),  -- RESERVATION_ATTEMPT, PAYMENT_SUCCESS, etc.
  user_id UUID,
  sku_id UUID,
  outcome VARCHAR(20),  -- SUCCESS, FAILED, THROTTLED
  failure_reason VARCHAR(255),
  timestamp TIMESTAMP WITH TIME ZONE,
  ip_address INET,
  device_fingerprint VARCHAR(255),
  metadata JSONB,
  previous_event_hash VARCHAR(64),
  event_hash VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  CONSTRAINT append_only CHECK (created_at >= NOW() - INTERVAL '1 second')
);

CREATE INDEX idx_audit_user ON audit_events(user_id, timestamp);
CREATE INDEX idx_audit_sku ON audit_events(sku_id, timestamp);
```

**Technology Recommendation**:
- Storage: PostgreSQL with constraint preventing updates/deletes
- Hashing: SHA-256 (industry standard)
- Backup: Immutable object storage (S3 with Object Lock)
- Retention: 7 years for financial compliance

---

### Decision 9: Observability & Monitoring Stack

**Selected Solution**: Prometheus + Grafana + Jaeger Tracing + ELK Stack
```
Services → Prometheus metrics
Services → Jaeger distributed tracing
Services → ELK logs (structured JSON)
Everything → Grafana dashboards + PagerDuty alerts
```

**Rationale**:
- Prometheus handles high-cardinality metrics (per-user, per-SKU)
- Jaeger provides distributed tracing (understand latency across services)
- ELK provides full-text search for debugging
- Grafana provides real-time alerting
- All tools are open-source and proven at scale

**Alternative Options**:

**Option A: CloudWatch (AWS-only)**
- Pros:
  - Integrated with AWS services
  - Simple setup (no infrastructure)
  - Cost-effective at scale
- Cons:
  - Vendor lock-in (only works with AWS)
  - Limited query capabilities (not SQL-like)
  - Higher costs for custom metrics
  - Long query latency (minutes)
- When it would be better: Single-cloud AWS deployments

**Option B: Datadog (Proprietary)**
- Pros:
  - Comprehensive (metrics, traces, logs in one platform)
  - Excellent UI/UX
  - Proactive alerting (ML-based)
  - Great support
- Cons:
  - **Expensive** ($1000+ per month for this volume)
  - Vendor lock-in
  - Difficult to own/customize
  - Data egress costs
- When it would be better: Well-funded teams prioritizing convenience

**Option C: New Relic (Proprietary)**
- Pros:
  - Easy integration
  - Good performance monitoring
  - Automatic anomaly detection
- Cons:
  - High cost ($500+ per month)
  - Vendor lock-in
  - Limited customization
- When it would be better: Application performance monitoring focus

**Option D: Self-hosted ELK Only (No Metrics/Tracing)**
- Pros:
  - Low cost (just infrastructure)
  - Full control
  - No vendor lock-in
  - Familiar for many teams
- Cons:
  - **No metrics** (cannot see throughput, latency percentiles)
  - **No distributed tracing** (difficult to understand slow requests)
  - Logs alone insufficient for production debugging
  - High operational overhead
- When it would be better: Log-only debugging needs

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Open-source (no vendor lock-in)
  - Comprehensive observability (metrics + traces + logs)
  - Scalable to 25k RPS
  - Cost-effective (<$500/month infrastructure)
  - Community support and integrations
- **Selected approach sacrifices**:
  - Operational overhead (must manage Prometheus, Jaeger, ELK clusters)
  - Not as polished UI as Datadog
  - Requires expertise to tune retention/storage
- **Why acceptable**:
  1. Long-term cost savings justify operational effort
  2. Open-source provides flexibility
  3. Team can customize as needed

**Key Metrics to Monitor**:
```
Critical Metrics (Oversell Protection):
- oversell_count: counter (MUST be 0)
- reserved_count: gauge
- sold_count: gauge
- Alert: IF oversell_count > 0 → IMMEDIATE page on-call

Performance Metrics:
- reservation_latency: histogram (P50, P95, P99)
- checkout_latency: histogram
- queue_length: gauge
- stock_depletion_rate: counter

Business Metrics:
- total_reservations: counter
- successful_checkouts: counter
- revenue: gauge
- user_fairness_metric: stddev of purchases per user

Alert Thresholds:
- reservation_latency_p95 > 120ms → Page on-call
- queue_length > 1M → Auto-scale
- payment_gateway_error_rate > 5% → Enable queue + retry
```

**Technology Recommendation**:
- Metrics: Prometheus (2 replicas for HA)
- Visualization: Grafana (dashboard templates)
- Tracing: Jaeger all-in-one (separate cluster)
- Logs: ELK (Elasticsearch, Logstash, Kibana)
- Alerting: Prometheus AlertManager + PagerDuty integration

---

### Decision 10: Service Communication Pattern

**Selected Solution**: Event-Driven with Kafka + REST for Synchronous Calls
```
Between services: Async via Kafka events
Client to API: Synchronous REST
Internal coordination: Kafka topics
```

**Rationale**:
- Kafka provides durability and replay capability for state changes
- REST for client-facing APIs (standard, browser-compatible)
- Decouples services (reservation service doesn't call checkout directly)
- Enables independent deployment
- Natural fit for event sourcing

**Alternative Options**:

**Option A: Synchronous REST Everywhere**
```
Client → Reserve → Queue Token Service → Checkout → Payment Gateway
```
- Pros:
  - Simplest implementation
  - Direct debugging (stack traces)
  - No event infrastructure needed
- Cons:
  - **Service failures cascade** (payment failure blocks checkout)
  - High latency (chain of synchronous calls)
  - Cannot implement eventual consistency
  - Tight coupling (cannot deploy independently)
- When it would be better: Simple 2-3 service systems

**Option B: gRPC for All Communication**
- Pros:
  - Lower latency than REST (binary protocol)
  - Streaming support
  - Strongly typed (protobuf)
- Cons:
  - Not browser-friendly (requires proxies)
  - Higher learning curve
  - Not suitable for public APIs (clients expect REST)
  - Requires custom tooling
- When it would be better: Internal service mesh (not public API)

**Option C: Message Queue Everywhere (RabbitMQ)**
- Pros:
  - True async (no blocking)
  - Request/reply pattern available
- Cons:
  - Request/reply pattern loses async benefits
  - More complex than needed
  - No ordering guarantees (unlike Kafka)
  - Message loss possible without persistence
- When it would be better: Complex routing and transformation

**Option D: Synchronous + Async Hybrid (No Clear Pattern)**
- Pros:
  - Flexibility
- Cons:
  - Inconsistent architecture
  - Difficult to reason about flow
  - Hard to onboard new developers
  - Debugging nightmare
  - High maintenance cost
- When it would be better: Never (anti-pattern)

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Resilient to service failures (async via Kafka)
  - Decoupled services (independent deployment)
  - Event history preserved (audit trail)
  - Natural scalability
- **Selected approach sacrifices**:
  - Eventual consistency (brief windows)
  - More infrastructure (Kafka)
  - Debugging distributed system (can be harder)
- **Why acceptable**:
  1. Kafka proven at scale (LinkedIn, Netflix, Uber)
  2. Synchronous failures unacceptable in this domain
  3. Event sourcing requirement aligns with Kafka

**API Contracts**:
```
REST API: Client → API Gateway
- POST /api/v1/products/{sku_id}/reserve
- POST /api/v1/orders/checkout

Kafka Events (Internal):
- reservation.created
- reservation.expired
- checkout.requested
- payment.succeeded
- payment.failed
- order.confirmed
```

**Technology Recommendation**:
- REST: Spring Boot Web
- Kafka: Confluent Cloud or self-hosted cluster
- Protocol: HTTP/2 for client requests
- Schema Registry: Confluent Schema Registry (protobuf/avro)

---

### Decision 11: Deployment Architecture

**Selected Solution**: Containerized Microservices on Kubernetes with Blue-Green Deployments
```
Git push → CI/CD Pipeline → Build Docker images → Push to registry
→ Deploy to K8s staging → E2E tests → Blue-green deploy to production
```

**Rationale**:
- Kubernetes provides auto-scaling, self-healing, rolling updates
- Containers ensure consistency across environments
- Blue-green deployment enables zero-downtime updates
- CI/CD pipeline automates testing and deployment
- Infrastructure as Code (Terraform) enables reproducibility

**Alternative Options**:

**Option A: Traditional VMs (AWS EC2, Auto Scaling Groups)**
- Pros:
  - Familiar to many ops teams
  - Full control over environment
  - Lower learning curve than K8s
- Cons:
  - Manual scaling (doesn't react fast enough for spikes)
  - Configuration drift over time
  - Slower deployment (AMI building, instance launch)
  - Higher operational overhead
  - Cannot meet 3-5 second scale-up requirement
- When it would be better: Stable, predictable workloads (<1k RPS)

**Option B: Serverless (AWS Lambda, Google Cloud Functions)**
- Pros:
  - Auto-scaling (handles spikes automatically)
  - Pay only for usage
  - No operational overhead
  - Cold start handled automatically
- Cons:
  - **Cold start latency** (100-500ms first invocation)
  - Cannot meet P95 ≤120ms reservation latency
  - Limited execution time (15 min timeout)
  - Difficult to maintain state
  - Cost unpredictable (25k RPS = expensive)
- When it would be better: Sporadic, unpredictable workloads

**Option C: Kubernetes on Self-Managed Cluster**
- Pros:
  - Full control
  - Can optimize for specific hardware
  - No cloud provider costs for control plane
- Cons:
  - **High operational burden** (manage etcd, API server, etc.)
  - Requires deep K8s expertise
  - Security patches take longer
  - Difficult to achieve HA (need 3+ masters)
  - Not cost-effective vs. managed services
- When it would be better: Large organizations with dedicated K8s team

**Option D: Docker Swarm**
- Pros:
  - Simpler than Kubernetes
  - Lower overhead
  - Good for small clusters (<50 nodes)
- Cons:
  - Less feature-rich than K8s
  - Smaller community (harder to find solutions)
  - Limited scheduling capabilities
  - Not suitable for high-throughput (cannot scale as effectively)
- When it would be better: Small teams with Docker experience

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Auto-scaling (handles spike to 25k RPS in seconds)
  - Self-healing (automatic restart of failed pods)
  - Rolling updates (zero-downtime deployments)
  - Infrastructure as Code (reproducible)
  - Excellent observability integration
- **Selected approach sacrifices**:
  - Operational complexity (K8s learning curve)
  - Higher cost than simple VMs (K8s infrastructure)
  - Requires skilled team to troubleshoot
- **Why acceptable**:
  1. Auto-scaling critical for 3-5 second spike handling
  2. K8s industry standard (large community)
  3. Long-term cost savings (pay only for peak during event)

**Deployment Timeline**:
```
T₀ - 30 seconds:
- Scale up: Reservation service 10 → 100 pods
- Scale up: Checkout service 5 → 50 pods
- Warm up Redis caches
- Verify health checks pass

T₀ + 3-5 seconds:
- All pods fully ready
- Start accepting traffic

T₀ + 30 minutes:
- Gradual scale down
- Close queue (reject new reservations)

T₀ + 2 hours:
- Return to baseline capacity
```

**Technology Recommendation**:
- Orchestration: Kubernetes 1.28+ (EKS/GKE/AKS managed)
- Container runtime: containerd (not Docker daemon)
- Deployment: Helm charts for templating
- GitOps: ArgoCD for declarative deployments
- Infrastructure: Terraform for cloud resources

---

### Decision 12: State Management for Reservation Expiry

**Selected Solution**: Scheduled Job with Database Scan + Redis Pub/Sub Notifications
```
Every 10 seconds: Query DB for expired reservations → Mark as EXPIRED → Invalidate cache
Redis Pub/Sub: Notify services of expiration for real-time updates
```

**Rationale**:
- Scheduled job handles bulk expiration without blocking requests
- Database scan ensures durability (survives service restarts)
- Redis Pub/Sub notifies services in real-time
- Per-reservation TTL in Redis enables fast lookup
- Separates expiration from request path (doesn't block reservations)

**Alternative Options**:

**Option A: In-Memory Timer (ScheduledExecutorService)**
```java
reservationExpireTimer.schedule(expireTask, 2, TimeUnit.MINUTES);
```
- Pros:
  - Zero database overhead
  - Precise timing
  - Simple to implement
- Cons:
  - **Data loss if service restarts** (pending expirations lost)
  - Memory overhead for timers (millions of timers)
  - Single service handles expiration (not HA)
  - Cannot recover after crash
- When it would be better: Non-durable operations, short TTLs (<minutes)

**Option B: Database Triggers**
```sql
CREATE TRIGGER expire_reservations AFTER INSERT ON reservations
BEGIN
  -- Schedule expiration event
END;
```
- Pros:
  - Durable (in database)
  - Automatic (no application logic)
- Cons:
  - Triggers add latency to every write
  - Difficult to test
  - Can cause unexpected cascades
  - Not all DBs support delayed triggers well
- When it would be better: Simple expirations tied to database state

**Option C: Distributed Timer (Clock Service)**
- Pros:
  - Centralized timer state
  - Can coordinate across services
- Cons:
  - Single point of failure (clock service)
  - Complex implementation
  - Synchronization overhead
  - Overkill for this problem
- When it would be better: Complex distributed scheduling needs

**Option D: Event Sourcing with Time Window Queries**
```
At read time: Check if reservation.created + 2 min < NOW()
```
- Pros:
  - No timers needed
  - Lazy evaluation (only checks when needed)
- Cons:
  - Cannot release stock immediately (stock appears reserved when expired)
  - Causes inaccurate availability counts
  - Must guard every read against expired state
- When it would be better: Non-critical expirations

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Durable (survives restarts)
  - Distributed (any instance can run scheduler)
  - Non-blocking (doesn't impact requests)
  - Real-time notifications via Pub/Sub
- **Selected approach sacrifices**:
  - Scheduled job introduces slight delay (10 second window)
  - Database scan cost
  - Slightly more complex (job + Pub/Sub)
- **Why acceptable**:
  1. 10-second delay acceptable for 2-minute TTL
  2. Database cost minimal (one query per 10 seconds)
  3. Pub/Sub ensures services see updates promptly

**Implementation**:
```
Scheduled Job (every 10 seconds):
SELECT * FROM reservations
WHERE status = 'RESERVED' AND expires_at < NOW()
UPDATE reservations SET status = 'EXPIRED' WHERE reservation_id IN (...)
INVALIDATE cache for affected SKUs
PUBLISH redis:expiration:channel {reservation_ids}

Services listening to redis:expiration:channel
Immediately update inventory counts
Re-offer stock to waiting users
```

**Technology Recommendation**:
- Scheduler: Spring Scheduling or Quartz
- Pub/Sub: Redis Pub/Sub
- Database: PostgreSQL transaction

---

### Decision 13: Payment Processing Strategy

**Selected Solution**: Async Payment Processing with Idempotency + Fallback Queue
```
Checkout request → Async call to payment gateway → Queue pending if timeout → Retry loop
```

**Rationale**:
- Async payment prevents blocking reservation (2-minute window)
- Idempotency key prevents double charging on retries
- Fallback queue handles gateway timeouts gracefully
- Enables graceful degradation if payment service is slow
- Complies with PCI-DSS (no payment data in our logs)

**Alternative Options**:

**Option A: Synchronous Payment (Block on Gateway)**
```
POST /checkout → Call payment gateway → Wait for response → Return
```
- Pros:
  - Immediate feedback to user
  - Simple implementation
  - Clear success/failure
- Cons:
  - **Cannot meet P95 ≤450ms checkout if gateway is slow** (gateway latency often 500ms+)
  - User experiences long waits
  - High timeout risk (gateway network issues)
  - Blocks reservation (prevents other checkouts)
- When it would be better: Low-frequency payments, fast gateway

**Option B: Fire-and-Forget (No Confirmation)**
```
POST /checkout → Queue payment → Return success immediately
User never knows if payment succeeded
```
- Pros:
  - Instant response
  - Never times out
- Cons:
  - **User doesn't know if payment worked**
  - Payment failures discovered later
  - Terrible user experience
  - Cannot issue order confirmation
  - Non-refundable if payment fails
- When it would be better: Never acceptable

**Option C: Webhook-based (Payment calls back)**
```
POST /checkout → Queue payment → Return pending
Payment gateway processes → Calls webhook → Updates order
```
- Pros:
  - Async processing (doesn't block)
  - Gateway can rate limit
  - Handles retries naturally
- Cons:
  - Webhook delivery failures (order status stuck)
  - Order state ambiguous until webhook arrives
  - Requires webhook security (signature verification)
  - User must poll for status
- When it would be better: Third-party processors (Stripe, Square)

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Meets latency SLO (P95 ≤450ms)
  - Handles gateway latency gracefully
  - Idempotent (safe to retry)
  - Clear feedback to user
- **Selected approach sacrifices**:
  - Slightly more complex (async + queue)
  - Depends on reliable message queue
  - Requires monitoring of payment queue
- **Why acceptable**:
  1. P95 ≤450ms requirement forces async approach
  2. Payment gateway response times often >200ms
  3. Idempotency keys prevent all double-charging risks

**Flow**:
```
POST /checkout
{
  "reservation_id": "res-456",
  "payment_method_id": "pm-789",
  "idempotency_key": "uuid"
}

1. Check idempotency (cache + DB)
2. If exists, return cached order status
3. If not, validate reservation still valid
4. Create Order with status=PENDING
5. Queue payment task: {order_id, amount, payment_method}
6. Return: {order_id, status: PENDING, message: "Processing payment..."}

Async Payment Processor (background):
1. Consume payment task from queue
2. Call payment gateway with idempotency key
3. If success: UPDATE order SET status=PAID
4. If failure: UPDATE order SET status=FAILED, reason=...
5. If timeout: Retry with exponential backoff (up to 10 retries)
6. If persistent failure: Manual intervention queue

User can poll: GET /api/v1/orders/{order_id} to check status
```

**Technology Recommendation**:
- Queue: Apache Kafka (durable, exactly-once)
- Retry: Exponential backoff (1s, 2s, 4s, 8s, 16s, 32s, 1m, 2m, 5m, 10m)
- Payment Gateway: Stripe/Square (they handle idempotency)
- Monitoring: Alert if payment queue grows >1000 (indicates gateway issues)

---

### Decision 14: Caching Invalidation Strategy

**Selected Solution**: Dual Invalidation (Immediate + TTL)
```
On every reservation/checkout:
1. Immediate: DELETE cache:{sku_id}:stock
2. TTL: SET cache with 5-second expiry (safety net)
```

**Rationale**:
- Immediate deletion ensures fresh data for next request
- 5-second TTL acts as safety net (prevents stale data if invalidation msg fails)
- Balances consistency and performance
- Fits flash sale pattern (stock updates every seconds)

**Alternative Options**:

**Option A: TTL-Only (No Immediate Invalidation)**
- Pros:
  - Simplest (set once, let expire)
  - No cascade invalidations
  - Predictable cache behavior
- Cons:
  - **Stale data for up to 5 seconds** (might show sold-out as available)
  - Users frustrated by inconsistency
  - Cannot provide accurate real-time stock
- When it would be better: Non-critical data, low volatility

**Option B: Immediate Invalidation Only (No TTL)**
```
Every reservation: DELETE cache:{sku_id}:stock
Cache miss → Query database
```
- Pros:
  - Always fresh data
  - No stale cache issues
- Cons:
  - High database load (every cache miss hits DB)
  - Invalidation message failures cause stale cache
  - No safety net if Redis crashes
  - Cache provides little benefit (high miss rate)
- When it would be better: Low-traffic systems

**Option C: Smart Invalidation (Time-based invalidation messages)**
```
On reservation: PUBLISH invalidation:{sku_id}:{timestamp}
Cache listens: If timestamp > cache.timestamp, invalidate
```
- Pros:
  - Precise invalidation
  - Handles out-of-order messages
  - Can track what changed when
- Cons:
  - Complex (requires message ordering)
  - Higher overhead
  - Risk of logical errors
- When it would be better: Complex cache hierarchies

**Option D: Cache-Aside with Conditional Refresh**
```
GET cache:{sku_id}:stock
If stale flag set: Refresh asynchronously
Return stale value while refreshing
```
- Pros:
  - Never blocks on cache refresh
  - Users get instant response
  - Reduces thundering herd
- Cons:
  - Users see stale data (acceptable for volatile data?)
  - Complex logic to determine staleness
  - Difficult to debug
- When it would be better: Content that's okay to be slightly stale

**Tradeoff Analysis**:
- **Selected approach gains**:
  - Fresh data (immediate invalidation)
  - Safety net (TTL prevents indefinite staleness)
  - Simple to implement
  - Handles both invalidation msg failures and Redis crashes
- **Selected approach sacrifices**:
  - Double operation (delete + re-populate)
  - Slightly more complex logic
- **Why acceptable**:
  1. Flash sale requires fresh stock counts (regulatory requirement)
  2. 5-second window acceptable (event dynamics change every second)
  3. Simple implementation reduces bugs

**Implementation**:
```
POST /api/v1/reserve
{
  // ... request body ...
}

Execute in transaction:
1. Acquire lock
2. Decrement stock
3. Create reservation
4. Commit transaction
5. INVALIDATE cache (outside transaction):
   - REDIS.DEL(f"stock:{sku_id}")
   - REDIS.EXPIRE(f"stock:{sku_id}:ttl", 5)  // safety net

Next GET /api/v1/products/{sku_id}/availability
1. CHECK REDIS for stock count
2. IF cache miss:
   SELECT available_stock FROM inventory WHERE sku_id
   SET REDIS for 5 seconds
3. RETURN stock count
```

**Technology Recommendation**:
- Cache: Redis with DEL for immediate invalidation
- TTL: 5 seconds for safety
- Monitoring: Track cache hit rate (should be >90% except during active event)

---

## Data Flow Architecture

### 1. Reservation Flow (Happy Path)
```
User Request
  ↓
API Gateway (Rate limit check, auth)
  ↓ (If passed)
POST /api/v1/reserve
  ↓
Reservation Service (Check idempotency key in Redis)
  ↓ (If new request)
Enqueue to Kafka: reservation.request
  ↓
Inventory Service (single consumer)
  ├─ Acquire Lock
  ├─ Check stock > 0
  ├─ Check user doesn't have active reservation
  ├─ Decrement stock
  ├─ Create reservation in DB
  ├─ Release lock
  └─ Publish: reservation.created
  ↓
Cache invalidation: DELETE cache:{sku_id}:stock
  ↓
Return: {reservation_id, status: RESERVED, expires_at: T+2min}
```

### 2. Checkout Flow
```
POST /api/v1/checkout
  ↓
Check idempotency (Redis + DB)
  ↓ (If new)
Validate reservation still active
  ↓
Create Order (status: PENDING)
  ↓
Enqueue: payment.request
  ↓
Payment Service (async processor)
  ├─ Call payment gateway (with idempotency)
  ├─ On success: Update order → PAID
  ├─ On failure: Update order → FAILED
  └─ On timeout: Retry queue
  ↓
Publish: order.confirmed or order.failed
  ↓
Notification Service listens
  └─ Send email confirmation
```

### 3. Reservation Expiry (Background)
```
Every 10 seconds:
  ├─ Query DB: SELECT * FROM reservations WHERE status='RESERVED' AND expires_at < NOW()
  ├─ UPDATE to EXPIRED
  ├─ Publish: reservation.expired
  └─ INVALIDATE cache
  ↓
Services receive expiry event
  └─ Update inventory counts
```

---

## Deployment Strategy

### Pre-Event (T₀ - 30 seconds)
```
1. Scale up services:
   - Reservation service: 10 → 100 pods
   - Checkout service: 5 → 50 pods
   - Payment processor: 2 → 20 pods

2. Warm up caches:
   - Pre-populate Redis with product data
   - Prime database connection pools
   - Load CDN edge caches

3. Health checks:
   - Verify all pods ready (Kubernetes readiness probes)
   - Database replication lag < 100ms
   - Kafka brokers healthy

4. Database optimization:
   - Increase connection pool size
   - Tune query plans if needed
```

### During Event (T₀ to T₀ + 30 minutes)
```
1. Monitor critical metrics:
   - Oversell count (MUST = 0)
   - Latency percentiles (P95 reservations)
   - Queue length
   - Stock depletion rate

2. Auto-scaling (Kubernetes HPA):
   - If queue length > threshold → Scale up
   - If CPU > 80% → Scale up
   - Max scale: 200 pods per service

3. Graceful degradation:
   - If payment gateway errors >5% → Enable retry queue
   - If database latency >200ms → Buffer writes to queue
```

### Post-Event (T₀ + 30 min onwards)
```
1. Close the sale:
   - Stop accepting new reservations (reject 429)
   - Allow pending checkouts to complete

2. Scale down gradually:
   - Reduce pod count over 30 minutes
   - Monitor for missed events

3. Cleanup:
   - Archive audit logs to cold storage (S3)
   - Clear Redis caches
   - Return database to normal parameters

4. Analysis:
   - Generate reconciliation reports
   - Compare sold count vs. inventory
   - Review error logs for issues
```

---

## Summary of Key Architectural Decisions

| Decision | Selected | Why | Main Tradeoff |
|----------|----------|-----|---------------|
| Load Balancing | CDN + API Gateway | Handles 250k RPS, DDoS protection | Slightly more infrastructure |
| Inventory Locking | Single-Writer + Kafka | Zero oversell, fair FIFO | +10-20ms latency per reservation |
| Cache Layer | Redis Cluster | Handles 250k RPS reads, HA | Operational complexity |
| Database | PostgreSQL + Replicas | ACID, strong consistency, proven | Primary is write bottleneck |
| Rate Limiting | Token Bucket + Queue | Fair distribution, burst handling | Complex implementation |
| Idempotency | Redis + DB constraint | Fast + Durable | Two places to maintain |
| Transactions | Saga + Event Sourcing | No blocking, full audit trail | Eventual consistency window |
| Audit Logging | WORM append-only | Tamper-proof, compliant | Storage overhead |
| Observability | Prometheus + Jaeger + ELK | Comprehensive, open-source | Operational overhead |
| Communication | Kafka + REST | Decoupled, resilient, async | Event infrastructure needed |
| Deployment | Kubernetes + Blue-Green | Auto-scaling, zero-downtime | K8s complexity |
| Expiry | Scheduled job + Pub/Sub | Durable, real-time | ~10 second lag |
| Payment | Async + Idempotency | Meets SLO, graceful degradation | Eventual consistency brief window |
| Cache Invalidation | Dual (immediate + TTL) | Fresh + Safe | Double operation |

---

## Conclusion

This architecture is designed to handle extreme traffic spikes (25k RPS writes, 250k RPS reads) while maintaining:
- **Zero oversell** (hard guarantee via single-writer pattern)
- **Sub-150ms latencies** (aggressive caching, optimized queries)
- **Fair distribution** (token bucket + FIFO queue)
- **Complete auditability** (event sourcing + WORM logs)
- **High reliability** (distributed patterns, graceful degradation)

Every decision explicitly considers alternatives and tradeoffs, making clear where we sacrifice one property for another and why that tradeoff is acceptable for this specific use case.
