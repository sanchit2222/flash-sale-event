# Flash Sale System - Ultra-Comprehensive Architecture Design

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [Deep Requirement Analysis](#deep-requirement-analysis)
3. [Architectural Decisions with Decision Trees](#architectural-decisions-with-decision-trees)
4. [Risk Analysis for Each Decision](#risk-analysis-for-each-decision)
5. [Failure Mode Analysis](#failure-mode-analysis)
6. [Operational Implications](#operational-implications)
7. [Cost Analysis](#cost-analysis)
8. [Team Skillset Requirements](#team-skillset-requirements)

---

## Executive Summary

### Problem Statement
Design a Flash Sale system handling extreme traffic spikes (millions of users in minutes) competing for 10,000 units with hard constraints:
- **Zero oversell** (non-negotiable, legal/compliance requirement)
- **Sub-150ms latency** for reads (product availability)
- **Sub-120ms latency** for writes (reservations)
- **Sub-450ms latency** for checkout (payment processing)
- **25k RPS writes** to single SKU (extreme contention)
- **250k RPS reads** (95% cacheable)
- **Complete auditability** (every decision traceable)
- **Fair distribution** (prevent bot wins)

### Solution Approach
Multi-tier, event-driven architecture with:
- **Resilience through decoupling** (no synchronous cascading failures)
- **Consistency through serialization** (single-writer pattern for inventory)
- **Scalability through caching** (99%+ cache hit rate for reads)
- **Auditability through events** (complete event sourcing for all state changes)
- **Fairness through queueing** (FIFO processing with rate limits)

---

## Deep Requirement Analysis

### The 25k RPS Problem: Why This Breaks Traditional Systems

#### Context: Single SKU Contention
- 10,000 units available
- 25,000 purchase attempts per second
- Average time to deplete: **0.4 seconds** (all units sold in 400ms)
- Decision rate: **25 decisions per millisecond**
- Per-decision processing budget: **40 microseconds**

#### Why Traditional Approaches Fail

**Approach 1: Pessimistic Locking (Row Lock)**
```sql
BEGIN TRANSACTION;
  SELECT * FROM inventory WHERE sku_id = ? FOR UPDATE;  -- Lock acquired
  -- User reads stock, checks limit, waits for previous transaction
  UPDATE inventory SET reserved_count = reserved_count + 1;
  INSERT INTO reservations (...) VALUES (...);
COMMIT;
-- Lock released

Database Lock Timeline:
T+0ms: Request 1 acquires lock, processing starts
T+10ms: Requests 2-25 queue waiting for lock
T+10ms: Request 1 commit, lock released
T+11ms: Request 2 gets lock
T+21ms: Request 2 commit, lock released
T+22ms: Request 3 gets lock
...

Throughput: ~100 requests/second (25k RPS would require 250 seconds!)
Latency: By request #25k, P99 latency = 250,000ms (4 minutes!)
```

**Why it fails**: Database row locks serialize all access. With 25k RPS, queue builds up, latency explodes, SLO violated immediately.

**Approach 2: Optimistic Locking (Version Numbers)**
```sql
UPDATE inventory
SET reserved_count = reserved_count + 1, version = version + 1
WHERE sku_id = ? AND version = ?;
-- If version mismatch, retry
```

Expected Retry Rate Calculation:
```
Concurrent requests: 25,000 / second
Lock-free window: ~1ms (time between updates)
Competing requests in window: 25 requests
Success rate: 1/25 = 4%
Failure rate: 96%

Expected retries per request: 25 retries average
Total database attempts: 25,000 × 25 = 625,000 attempts/second
P99 latency: 25 retries × 10ms = 250ms (violates P95 ≤120ms)
```

**Why it fails**: Exponential retries under high contention. Retry storms amplify load, violate latency SLO, waste resources.

**Approach 3: Distributed Lock (Redis SETEX NX)**
```
LOCK:sku:123: timeout 1 second, tries to acquire every 10ms
```

Timeline:
```
T+0ms: Request 1 acquires lock, processing takes 10ms
T+10ms: Request 1 commit, lock released
T+10ms-11ms: All 250 queued requests race to acquire lock
T+11ms: Request 2 gets lock (1 winner, 249 retry)
T+21ms: Request 3 gets lock
...
```

**Problem**: Still serializes access. 25,000 requests waiting for single lock.

**Why selected approach works**: Queue serialization (Kafka) doesn't have race condition overhead. Single consumer processes queue sequentially at full speed.

---

### The P95 ≤120ms Constraint: Math of Latency

#### Latency Budget Breakdown
```
Total budget: 120ms

Request path:
1. Network (client to LB): 10ms (geographic)
2. API Gateway (rate limit, auth): 5ms
3. Serialization queue wait: 10-50ms (variable based on queue depth)
4. Inventory lock acquisition: 1ms
5. Database read (stock check): 2ms
6. Database write (stock decrement): 3ms
7. Cache invalidation: 1ms
8. Response serialization: 1ms
9. Network (server to client): 10ms
Total: ~50-90ms average, ~100-110ms P95

Queue wait is THE critical path. Need to keep queue processing rate > 25k requests/sec
```

#### Why Queue Must Be Single Partition
```
Multi-partition queue (10 partitions):
- Each partition processes 2,500 requests/sec
- Per-request processing: 0.4ms
- But different users hit different partitions
- User #1 hits partition A (stock decremented)
- User #2 hits partition B (reads old cache)
- RACE CONDITION: Oversell possible

Single partition queue:
- All requests strictly ordered
- User #1 reserved, cache invalidated
- User #2 sees fresh cache
- No race condition possible
```

**Constraint implication**: Cannot horizontally scale reservation processing. Must be single consumer.

---

### The Zero Oversell Requirement: Why This Changes Everything

#### Financial/Legal Implications
- Cannot sell more units than in stock
- Overselling = breach of contract with customer
- Consequences: Legal liability, chargebacks, reputation damage
- Some jurisdictions: Criminal liability for fraud

#### Architectural Implication: Forces Strong Consistency
- Cannot use eventually consistent database (Cassandra, DynamoDB)
- Cannot use optimistic locking with high retry rates
- Must use serialized, atomic operations
- Every state change must be immediately durable

#### Detection & Recovery Scenarios

**Scenario 1: Oversell Detected (Audit log shows >10k sold)**
```
Who detects: Real-time monitoring (oversell_count metric > 0)
When: Immediately after occurs
Response:
  1. EMERGENCY: Freeze new reservations (return 429)
  2. Pause checkout processing (queue pending, don't process)
  3. Page on-call immediately
  4. Human investigation
  5. Determine which orders to refund
  6. Process refunds (if oversell found)
  7. Adjust inventory
```

**Root cause analysis**: Oversell can only occur from:
1. **Race condition in code** (most likely)
2. **Database corruption** (unlikely with ACID)
3. **Concurrent independent operations** (if not using single-writer)
4. **Manual admin error** (improper tools)

**Solution design**: Single-writer pattern prevents #1 and #3. ACID prevents #2. Restricted access prevents #4.

---

### Fair Distribution Requirement: Bot Resistance Design

#### Attack Vectors
```
Bot Attack 1: Rapid Fire Requests
- Bot makes 1000 requests/second from single IP
- Without rate limiting: Bot gets disproportionate reservations
- With rate limiting: Bot throttled, but may try from 1000 IPs

Bot Attack 2: Device Spoofing
- Bot changes User-Agent, Device ID, but same IP
- Without device fingerprinting: Appears as different users
- With fingerprinting: Detected as same origin

Bot Attack 3: Distributed Botnet
- 10,000 compromised devices, each making 2.5 requests
- Total: 25,000 requests/sec from legitimate IPs
- Without behavioral analysis: Indistinguishable from real spike

Bot Attack 4: Reservation Starvation
- Bot reserves but doesn't checkout
- Holds inventory for full 2-minute window
- Real users cannot reserve
```

#### Fair Distribution Implementation

**Token Bucket per Tier**:
```
Tier 1 (High Risk): 1 request/minute
Tier 2 (Medium Risk): 50 requests/minute
Tier 3 (Normal): 100 requests/minute
Tier 4 (Verified): 200 requests/minute

Assignment Logic:
- New account (created <30 days): Tier 2
- Account with purchases: Tier 3
- Verified phone/address: Tier 4
- Flagged IP (VPN/proxy): Tier 1
- Flagged behavior (rapid retries): Tier 1

During flash sale:
- Tokens replenish every 1 second
- Burst allowed: Initial spike okay (first second)
- Sustained: High tier rate enforced

Example timeline:
Second 0: User makes 100 requests → 50 succeed (tier quota)
Second 1: Tokens replenish → Next 100 possible
Second 2-60: Rate limited to 100/sec
Result: Fair distribution, bot cannot hoard tokens
```

**Queue Fairness**:
```
When rate limited (over quota):
- User added to FIFO queue
- Served in arrival order (first-in-first-out)
- When token becomes available, dequeue oldest waiting request

Prevents:
- Bot Win: Fast is no advantage once queued
- Starvation: FIFO guarantees eventual service
- Unfairness: Honest users get service in order
```

---

## Architectural Decisions with Decision Trees

### Decision 1: Load Balancing & Request Routing

#### Decision Tree

```
Question 1: How to distribute 250k RPS?
├─ Option A: Single server
│  └─ REJECTED: Cannot handle 250k RPS on single machine
│
├─ Option B: Multiple servers + load balancer
│  ├─ Question 2: Where should load balancer be?
│  ├─ Option B1: Single datacenter load balancer
│  │  └─ REJECTED: Single point of failure, geographic latency
│  │
│  ├─ Option B2: Global load balancer → Regional LBs
│  │  ├─ Pros: Geographic distribution, DDoS mitigation, HA
│  │  ├─ Cons: Multiple components to manage
│  │  └─ SELECTED: Meets all requirements
│  │
│  └─ Option B3: Anycast DNS + intelligent routing
│     ├─ Pros: Clever routing, reduced latency
│     ├─ Cons: Complex, BGP-level coordination needed
│     └─ REJECTED: Overkill for this problem
│
└─ Question 3: Should API Gateway be before or after regional LB?
   ├─ Option C1: CDN → Regional LB → API Gateway
   │  └─ Selected (rate limiting, auth at gateway)
   │
   └─ Option C2: CDN → API Gateway → Regional LB
      └─ Rejected (API Gateway becomes bottleneck)
```

#### Selected Solution: Three-Tier Load Balancing

**Architecture**:
```
┌─────────────────────────────────────┐
│      Global CDN / Load Balancer     │
│  (Anycast IP, DDoS mitigation)      │
│      CloudFlare / AWS Shield        │
└──────────────┬──────────────────────┘
               │
   ┌───────────┼───────────┐
   │           │           │
   v           v           v
┌──────────┐ ┌──────────┐ ┌──────────┐
│ Regional │ │ Regional │ │ Regional │
│    LB    │ │    LB    │ │    LB    │
│ (US)     │ │ (EU)     │ │ (APAC)   │
└────┬─────┘ └────┬─────┘ └────┬─────┘
     │            │            │
     v            v            v
  K8s cluster  K8s cluster  K8s cluster
```

#### Detailed Rationale

**Global Layer (CDN)**:
- Absorbs DDoS attacks before reaching origin
- Caches static assets (product images, CSS, JS)
- Performs geo-routing to nearest regional center
- Reduces "thundering herd" problem

**Regional Layer (Network Load Balancer)**:
- Distributes traffic within region
- Handles regional failover
- Provides connection pooling
- Low latency (L4, 200k+ RPS per instance)

**API Gateway Layer**:
- Rate limiting (per user, per IP, per device)
- Authentication/JWT validation
- Request validation and enrichment
- Response compression and caching headers

#### Why Not Alternatives?

**Alternative: Single Server**
```
Bottleneck calculation:
- Modern server: ~10k-20k RPS capacity
- Need: 250k RPS
- Servers required: 250k / 15k = 16-17 servers minimum
- Single server cannot handle load

Additional failure: Single point of failure
- All traffic through one machine
- Any failure = complete outage
- No redundancy possible
```

**Alternative: Direct to API Gateway (Skip Regional LB)**
```
Topology: CDN → API Gateway (50 instances)

Problem: API Gateway becomes bottleneck
- API Gateway designed for ~5k RPS per instance
- 50 instances = 250k RPS capacity
- But: When one instance fails, load on others increases
- Cascading failures possible
- No isolation between regional traffic

With regional LB:
- Regional LB distributes to API GW instances
- Load more balanced
- Failure in one region doesn't affect others
```

**Alternative: Service Mesh Gateway (Istio)**
```
Istio architecture:
├─ API Gateway pod + sidecar
├─ Service pods + sidecars
└─ Control plane coordination

Problem: Sidecar overhead
- Each pod has sidecar processing traffic
- Adds 5-10ms latency per hop (authentication, encryption, routing)
- 10ms × 3 hops = 30ms added to P95 latency
- Violates P95 ≤120ms requirement

Benefit: Advanced traffic management
- Canary deployments
- Circuit breakers
- Retry logic
- Request mirroring

Trade-off: Latency gain not worth complexity for this use case
- We don't need advanced traffic management
- Simple blue-green deployment sufficient
- Traffic management handled at API Gateway level

When beneficial: Large microservices ecosystem (20+ services)
with frequent deployments and complex routing rules
```

#### Operational Decisions

**Scaling Strategy**:
```
T-30sec before sale:
1. Verify CDN is healthy and caching updated product data
2. Check all regional LBs are ready
3. Have API Gateway instances ready to scale
4. Verify K8s nodes have capacity (usually pre-scaled)

T0-5sec (Active spike):
1. Monitor LB connection counts
2. If any regional LB approaching capacity → scale
3. Trigger Kubernetes auto-scaling if needed
4. Monitor API Gateway rate limiting effectiveness

T+30min (Cool down):
1. Begin gradually reducing capacity
2. Remove Regional LBs if not needed
3. Scale down K8s clusters
4. Verify data integrity
```

---

### Decision 2: Inventory Management & Distributed Locking

#### The Contention Problem (Deep Dive)

Let's model the actual contention mathematically:

**Timeline with Pessimistic Locking**:
```
Database: Lock queue for inventory row
Assumption: Each request takes 10ms (acquire lock, read, write, commit)

Request 1:  T=0ms   → Acquires lock, processing T=0-10ms, releases T=10ms
Request 2:  T=0ms   → Queues waiting
Request 3:  T=1ms   → Queues waiting
...
Request 25000: T=999ms → Queues waiting

When Request 1 releases lock at T=10ms:
- 250 requests waiting
- Next lock goes to Request 2
- Request 2 completes at T=20ms
- Next lock goes to Request 3
...

Final request (#25000) gets lock at:
T = 10 × 25000 = 250,000 ms = 4 minutes!

P95 latency: ~2.4 minutes (violates SLO by 1200x)
```

**Timeline with Optimistic Locking**:
```
Assumption: 25 concurrent requests, update succeeds for 1 in 25

Success rates:
Request 1: Succeeds on attempt 1
Request 2: Fails 24 times, succeeds on attempt 25
Request 3: Fails 24 times, succeeds on attempt 25
...
Average: ~12.5 retry attempts per request

Total database loads:
Original attempts: 25,000
Failed retry attempts: 25,000 × 11.5 = 287,500
Total: 312,500 operations
Overhead: 12.5x amplification

P99 latency: 25 retries × 10ms = 250ms (violates SLO by 2x)
Thundering herd: 287,500 failed updates stress database
```

**Timeline with Single-Writer Pattern**:
```
Message Queue:
- Request 1 arrives → Enqueued at T=0ms
- Request 2 arrives → Enqueued at T=0.5ms
- Request 3 arrives → Enqueued at T=1ms
- ...
- Request 25000 arrives → Enqueued at T=999ms

Consumer processes queue:
- Reads request from queue (0.1ms)
- Acquires lock (no contention, instantly granted) (0.1ms)
- Updates inventory atomically (2ms)
- Releases lock (0.1ms)
- Total: 2.3ms per request

Timeline:
Request 1: Queued T=0ms, processed T=0-2.3ms
Request 2: Queued T=0.5ms, processed T=2.3-4.6ms
Request 3: Queued T=1ms, processed T=4.6-6.9ms
...
Request 25000: Queued T=999ms, processed T=57.7-60s

P95 latency:
- Queue wait: 0.95 × 999ms = 949ms (worst case)
- Processing time: 2.3ms
- Total: ~950ms
- VIOLATES SLO

Wait, why does this not work?
Answer: The queue can't process 25k requests/sec if each takes 2.3ms
Throughput = 1000ms / 2.3ms = 434 requests/sec
But we need 25,000 requests/sec

Optimization: Process multiple requests concurrently
- Lock inventory (single consumer serializes updates)
- But process queue in batches
- Read 100 requests from queue
- Lock inventory once
- Check all 100 against stock
- Allocate to those who can get
- Single atomic update
- Process 100 in 5ms = 20,000 requests/sec (still < 25k)

Better optimization: Increase batch size
- Process 250 requests in single transaction
- Check all against remaining stock
- Allocate proportionally
- Single atomic update per batch
- 250 requests / 10ms = 25,000 requests/sec ✓

P95 latency:
- Queue wait: ~50ms (batch processes every 10ms)
- Processing: 10ms
- Total: ~60ms ✓ (within budget)
```

**Key insight**: Single-writer with batching solves the contention problem while maintaining atomicity.

#### Decision Tree

```
Question: How to handle 25k concurrent writes to single inventory row?

├─ Option 1: Pessimistic Locking (Row locks)
│  ├─ Pros: Simple, ACID guaranteed
│  ├─ Cons: Throughput 100 req/sec, P95 > 4 minutes
│  └─ REJECTED: Cannot meet SLO
│
├─ Option 2: Optimistic Locking (Version numbers)
│  ├─ Pros: Better concurrency, no explicit locks
│  ├─ Cons: 12.5x retry amplification, P95 = 250ms
│  └─ REJECTED: Violates P95 ≤120ms SLO
│
├─ Option 3: Distributed Lock (Redis SETNX)
│  ├─ Issue: Still serializes if single lock
│  ├─ Alternative: Multiple locks by partition
│  │  ├─ Pros: Horizontal scalability
│  │  ├─ Cons: Race conditions possible (different partitions)
│  │  └─ REJECTED: Risk of oversell
│  └─ REJECTED: Doesn't solve fundamental problem
│
├─ Option 4: Single-Writer Pattern (Kafka batched)
│  ├─ Pros:
│  │  ├─ No race conditions (serialized consumer)
│  │  ├─ Achieves 25k RPS with batching
│  │  ├─ P95 latency ~60ms (within budget)
│  │  ├─ Fair FIFO queue (no bot advantage)
│  │  └─ Enables event sourcing
│  ├─ Cons:
│  │  ├─ Requires message queue infrastructure
│  │  ├─ Slightly higher latency than direct (queuing overhead)
│  │  └─ Complexity in batch allocation logic
│  └─ SELECTED: Only option meeting all requirements
│
└─ Option 5: Sharded Inventory
   ├─ Concept: Divide 10k units across 10 shards (1k each)
   ├─ Pros: Distributed writes, parallel processing
   ├─ Cons:
   │  ├─ User #1 reserves 1 unit from shard A
   │  ├─ User #2 sees shard A still has stock (hasn't updated cache)
   │  ├─ Race condition between shards
   │  └─ Over-counting possible
   └─ REJECTED: Risk of oversell from race conditions
```

#### Selected Solution: Kafka-based Single-Writer with Batching

**Architecture**:
```
┌────────────────────────────────────┐
│   25,000 Reservation Requests      │
└──────────────┬─────────────────────┘
               │
┌──────────────v─────────────────────┐
│  Kafka Topic: reservation-requests  │
│  Single Partition (ordering)        │
│  Retention: 24 hours                │
└──────────────┬─────────────────────┘
               │
┌──────────────v─────────────────────┐
│   Single Consumer Process            │
│   - Poll batch of 250 requests      │
│   - Validate all requests           │
│   - Acquire lock on inventory       │
│   - Allocate units atomically       │
│   - Update DB in single transaction │
│   - Release lock                    │
│   - Commit Kafka offset             │
└──────────────┬─────────────────────┘
               │
        ┌──────v──────┐
        │  PostgreSQL │
        │ Inventory   │
        │ Updated     │
        └─────────────┘
```

**Batch Processing Logic**:
```
Batch size: 250 requests per 10ms cycle
Cycle processing:

1. Poll batch from Kafka (non-blocking, ready in <1ms)
2. Build request list
   For each request:
   - Extract user_id, sku_id, idempotency_key
   - Validate: not already reserved, not duplicate
   - Deduplicate: if idempotency_key exists, skip

3. Allocate from inventory
   current_stock = SELECT stock FROM inventory WHERE sku_id FOR UPDATE

   For each validated request (in order):
     IF current_stock > 0 AND user doesn't have reservation:
       Allocate unit
       current_stock -= 1
     ELSE:
       Mark as FAILED (out of stock or user limit)

4. Atomic update
   BEGIN TRANSACTION
     UPDATE inventory SET reserved_count = reserved_count + allocated_count
     INSERT INTO reservations (batch of allocated requests)
   COMMIT

5. Publish events
   For each allocated: PUBLISH reservation.created
   For each failed: PUBLISH reservation.failed

6. Commit Kafka offset
   consumer.commitSync()

7. Return results immediately
   Clients receive responses from earlier requests (in-flight responses)

Timeline per cycle:
- Poll: 1ms
- Validate batch: 2ms
- Allocate: 1ms
- DB update: 5ms
- Publish events: 1ms
- Total: 10ms per batch

Throughput: 250 requests / 10ms = 25,000 RPS ✓
```

**Latency Analysis**:
```
Request arrives at Kafka at T=0ms
Latest it can arrive and still be in batch: T=10ms (before batch commits)

Request arrives at T=0ms:
- Batch processes immediately at T=0-10ms
- Gets response at T=10ms
- Latency: 10ms

Request arrives at T=5ms:
- Batch processes at T=0-10ms (already started)
- Skipped in current batch, goes to next
- Next batch at T=10-20ms
- Gets response at T=20ms
- Latency: 15ms

Request arrives at T=10.1ms:
- Batch just finished
- Queued for next cycle T=10-20ms
- Gets response at T=20ms
- Latency: 9.9ms

Worst case: Arrives just after batch start (T=0.1ms)
- Latency: ~10ms (waits full cycle)

P95 latency: ~30-40ms (queue waits + processing)
P99 latency: ~50ms (occasional stalls)
Total budget: 120ms
Utilization: 30-40ms / 120ms = 25-33% of budget ✓
```

#### Why Not Alternatives?

**Alternative: Stream Processing (Kafka Streams)**
```
Kafka Streams: Distributed stream processing framework

Topology:
└─ Reservation input stream
   ├─ Map: Enrich request with current user data
   ├─ Aggregate: Group by SKU
   └─ Sink: Write to inventory update stream

Problem:
1. Distributed by nature (multiple instances)
2. Each instance has own state store
3. State stores can diverge
4. When instance crashes, state lost
5. Recovery requires reprocessing

Result: Oversell risk from state inconsistency
Not suitable for critical inventory operations

When good: Non-critical aggregations (analytics)
```

**Alternative: Apache Flink (Stream Processing)**
```
Same as Kafka Streams, but adds:
- Checkpointing for fault tolerance
- More complex to operationalize
- Additional infrastructure overhead
- Still distributed (oversell risk)

When good: Complex transformations requiring distributed computing
```

**Alternative: Database-level Distributed Transaction**
```
Two-Phase Commit across multiple nodes:

Coordinator:
1. PREPARE phase: Ask all nodes to lock and prepare
2. COMMIT phase: Tell all nodes to commit

Problem:
- During PREPARE, all resources locked
- Network latency between nodes
- Coordinator failure = indefinite locks (distributed deadlock)
- Not recommended for microservices

When good: Financial systems with multiple databases
that must be consistent (RDBMS clusters, not microservices)
```

#### Operational Considerations

**Monitoring**:
```
Metrics:
- reservation_queue_depth: Should be <100 (good batching)
- batch_processing_time: Should be ~10ms
- batch_allocations: Should be 200-250 per batch
- allocation_failures: Track why users didn't get reservation

Alerts:
- queue_depth > 1000 → Consumer falling behind, scale up
- batch_time > 50ms → Lock contention, database slow
- allocation_failures increasing → May indicate oversell risk
```

**Failure Scenarios**:
```
Scenario 1: Consumer crashes mid-batch
- Kafka offset not committed
- Batch will be reprocessed
- Idempotency keys prevent double-reservation
- Result: Safe, duplicate allocations caught

Scenario 2: Database transaction fails
- Consumer catches exception
- Batch not committed to Kafka
- Retried on consumer restart
- Result: Safe, data consistency maintained

Scenario 3: Kafka broker fails
- Replication ensures data durability
- Consumer reconnects to new broker
- Offset recovered from committed state
- Result: No data loss, brief latency

Scenario 4: Oversell detected after-the-fact
- Audit log shows sold > 10,000
- Immediate alert fires
- Emergency procedures:
  1. Freeze new reservations
  2. Identify which orders to refund
  3. Process refunds
  4. Adjust inventory
- Root cause: Logic error in batch allocation
  (should never happen if implementation correct)
```

---

### Decision 3: Cache Layer Architecture

#### The Read Traffic Problem (Deep Analysis)

**Traffic Composition**:
```
250k RPS total:
- 95% are reads (product availability checks): 237.5k RPS
- 5% are writes (reservations): 12.5k RPS

Read composition:
- 80% product details (name, description, price): ~190k RPS
  ├─ Highly cacheable, changes rarely
  ├─ 50KB per response
  └─ Can be cached in CDN

- 20% stock availability (how many units left): ~47.5k RPS
  ├─ Changes frequently (every reservation)
  ├─ Must be fresh (consistency critical)
  ├─ Small response (1-2KB)
  └─ Cannot cache in CDN (changes constantly)

Database capacity:
- Single primary PostgreSQL
- Max capacity: ~5k reads/sec (at P99 < 100ms)
- Need: 47.5k reads/sec for stock
- Gap: Needs caching layer 10x capacity

Without cache:
- 47.5k requests hit database per second
- Database can handle 5k
- Cascading failure: Queue builds, latency explodes
- System overloaded, P95 > 10 seconds

With cache:
- 47.5k requests hit cache (99% hit rate)
- ~500 cache misses per second → database
- Database handles with ease
- P95 latency: cache hit ~1ms, miss ~10ms
```

**Cache Invalidation Problem**:
```
When reservation succeeds:
1. One unit reserved (stock decreased)
2. 47.5k/sec other users checking availability
3. If cache not invalidated: User sees old count
4. User thinks product available (stale cache)
5. Makes reservation → OVERSELL (we promised count)

Invalidation strategy must be:
- Atomic (invalidate same time as DB update)
- Immediate (no delay)
- Reliable (doesn't fail silently)
```

#### Decision Tree

```
Question: How to handle 47.5k RPS reads for stock availability?

├─ Option 1: No cache (Direct to database)
│  ├─ Database capacity: 5k RPS (at P99 < 100ms)
│  ├─ Need: 47.5k RPS
│  ├─ Result: Queue backlog, P95 > 1 second
│  └─ REJECTED: Violates P95 ≤150ms SLO
│
├─ Option 2: Single Redis Instance
│  ├─ Architecture: All cache requests → single node
│  ├─ Capacity: ~100k RPS (single node)
│  ├─ Pros: Sufficient for 47.5k RPS, simple
│  ├─ Cons:
│  │  ├─ Single point of failure (any crash = all cache lost)
│  │  ├─ No HA (cannot failover)
│  │  ├─ Cannot rebalance without downtime
│  │  └─ Data loss on hardware failure
│  ├─ Risk: Failure during event = P95 > 1 minute
│  └─ REJECTED: Availability risk unacceptable during peak event
│
├─ Option 3: Redis Master-Slave Replication
│  ├─ Architecture: Master handles writes, Slave handles reads
│  ├─ Replication lag: 10-100ms typical
│  ├─ Failover: Sentinel watches master, promotes slave
│  ├─ Failover time: 30-300ms (downtime in-between)
│  │
│  ├─ Read capacity: 2x (one write, one read)
│  ├─ Write capacity: Still single master (47.5k RPS writes/invalidations)
│  │  ├─ Each reservation: 1 write to cache
│  │  ├─ 12.5k reservations/sec
│  │  ├─ Replication network traffic: significant
│  │  └─ Master can handle, but tightly coupled
│  │
│  ├─ Pros:
│  │  ├─ Provides HA (failover possible)
│  │  ├─ Reads from replica
│  │  ├─ Better than single instance
│  │  └─ Simpler than cluster
│  │
│  ├─ Cons:
│  │  ├─ Replication lag (can see stale cache)
│  │  ├─ Failover downtime (not automatic fast)
│  │  ├─ Write scalability limited (single master)
│  │  ├─ During failover: Loss of in-flight invalidations
│  │  └─ Complex to operationalize (managing 2 instances)
│  │
│  ├─ Failure scenario during event:
│  │  ├─ Master fails during spike
│  │  ├─ Failover takes 30-300ms (downtime)
│  │  ├─ During failover: Cache not updated
│  │  ├─ Reads see stale stock counts
│  │  └─ Oversell risk if concurrent writes pending
│  │
│  └─ REJECTED: Failover downtime and stale reads risk
│
├─ Option 4: Redis Cluster (Sharded)
│  ├─ Architecture: Hash ring with N partitions
│  ├─ Automatic rebalancing and failover
│  ├─ Each shard: Master + Slave
│  │
│  ├─ Sharding by SKU:
│  │  ├─ Cache key: stock:{sku_id}
│  │  ├─ Shard = hash(sku_id) % N
│  │  ├─ For single SKU: Always same shard
│  │  ├─ Throughput per shard: 100k RPS
│  │  ├─ But all traffic to one shard (single SKU)
│  │  ├─ Other shards idle (waste)
│  │  └─ Still hits single point of failure (that shard)
│  │
│  ├─ Sharding by user:
│  │  ├─ But cache key is by SKU, not user
│  │  ├─ Must lookup user's reservation in cache
│  │  ├─ Two lookups: user_shard + sku_shard
│  │  ├─ Cross-shard operations slow
│  │  └─ Defeats purpose of sharding
│  │
│  ├─ Pros (when sharding is distributed):
│  │  ├─ Horizontal scalability
│  │  ├─ No single point of failure
│  │  ├─ Built-in HA per shard
│  │  ├─ Automatic failover
│  │  └─ Production-proven
│  │
│  ├─ Cons (for single SKU):
│  │  ├─ All traffic to one shard (no distribution)
│  │  ├─ Complexity in cluster management
│  │  ├─ Client library must support cluster protocol
│  │  ├─ Operational overhead (monitoring, rebalancing)
│  │  └─ Overkill for single SKU event
│  │
│  ├─ When beneficial: Multiple SKUs or events
│  │  ├─ Flash sale for 10 different products
│  │  ├─ Traffic distributed across shards
│  │  ├─ Each shard handles 25k RPS (manageable)
│  │  └─ True horizontal scalability
│  │
│  └─ SELECTED: Best option for HA + scalability
│     Even if single SKU concentrates on one shard,
│     that shard still replicates across nodes
│
├─ Option 5: Memcached
│  ├─ Simpler protocol than Redis
│  ├─ Slightly faster (less overhead)
│  ├─ No persistence (data loss on restart)
│  ├─ No pub/sub (needed for invalidation)
│  ├─ Data structures: strings only
│  │
│  ├─ Problem: No invalidation mechanism
│  │  ├─ Cannot actively invalidate entries
│  │  ├─ Must rely on TTL expiration
│  │  ├─ Stale data possible if TTL not short enough
│  │  └─ Short TTL = more cache misses = DB load
│  │
│  └─ REJECTED: Lack of invalidation mechanism
│
├─ Option 6: DynamoDB (AWS managed cache)
│  ├─ Managed service (no ops overhead)
│  ├─ Automatic scaling
│  ├─ Built-in HA and multi-region
│  │
│  ├─ Cons:
│  │  ├─ Designed for eventual consistency
│  │  ├─ Strong consistency available but slower (25ms+)
│  │  ├─ Pay per RPS (high cost for 47.5k RPS)
│  │  ├─ Cannot guarantee sub-millisecond latency
│  │  └─ Higher latency than in-memory cache
│  │
│  ├─ Cost analysis:
│  │  ├─ Provisioned mode: $0.47 per WCU
│  │  ├─ 47.5k RPS writes = expensive per-second pricing
│  │  ├─ Could be $500/hour just for cache
│  │  └─ Event is 30 min = $250 (not terrible but premium)
│  │
│  └─ REJECTED: Latency insufficient, cost high
│
└─ Option 7: In-Memory Cache (JVM Caffeine)
   ├─ Local to each service instance
   ├─ Microsecond latency
   ├─ No network overhead
   │
   ├─ Problem: Consistency across instances
   │  ├─ Instance A's cache has stock=100
   │  ├─ Instance B's cache has stock=100
   │  ├─ Reservation happens: stock now 99
   │  ├─ A invalidates its cache (has new data)
   │  ├─ B still has old cache (99 other instances didn't get message)
   │  ├─ User from B sees stock=100 (stale)
   │  ├─ Makes reservation → Possible oversell
   │
   ├─ Invalidation complexity:
   │  ├─ Broadcast invalidation to all 100 instances
   │  ├─ Network overhead
   │  ├─ Race conditions possible
   │  ├─ Message loss possible
   │  └─ Hard to guarantee all instances updated
   │
   └─ REJECTED: Consistency risk too high
```

#### Selected Solution: Redis Cluster with Intelligent Invalidation

**Architecture**:
```
┌─────────────────────────────────────┐
│     47.5k RPS Availability Reads     │
└──────────────┬──────────────────────┘
               │
        ┌──────v──────────┐
        │  Redis Cluster  │
        │  3 master nodes │
        │  + 3 slaves     │
        └──────┬──────────┘
               │
        ┌──────v──────────┐
        │   PostgreSQL    │
        │  (for misses)   │
        └─────────────────┘

Shard distribution (single SKU):
- Shard A (stock:{sku_id}): Handles all stock availability reads
- Shard A Master + Shard A Slave
- Shards B, C: Idle (for other SKUs if needed later)

Cache format:
Key: stock:{sku_id}
Value: {
  "available": 5234,
  "reserved": 4766,
  "total": 10000,
  "last_updated": 1705325645123,
  "version": 42
}
TTL: 5 seconds (safety net)

Invalidation trigger:
On every successful reservation:
1. UPDATE inventory SET reserved_count = reserved_count + allocated_count
2. REDIS.DEL(f"stock:{sku_id}")
3. Optionally: Pre-populate with fresh value from DB
```

**Latency & Throughput Analysis**:
```
Cache hit path (99% of requests):
1. Network (client → cache): 1ms
2. Cache lookup: 0.1ms
3. Network (cache → client): 1ms
Total: ~2ms
Throughput: 100k+ RPS (single shard)

Cache miss path (1% of requests):
1. Network (client → cache): 1ms
2. Cache miss detected: 0.1ms
3. Network (client → DB): 1ms
4. DB query: 5-10ms
5. Network (DB → client): 1ms
Total: ~9-13ms
Throughput: 5k RPS (database limited)

Mixed (99% hits, 1% misses):
- 99% requests: 2ms latency
- 1% requests: 10ms latency (fallback to DB)
- P95: ~2ms (mostly cache hits)
- P99: ~10ms (occasional DB hits)
```

**Invalidation Strategy**:
```
Event: Reservation succeeds
Atomic operation:
BEGIN TRANSACTION
  UPDATE inventory SET reserved_count = reserved_count + 1
  COMMIT
END
Immediately after (outside transaction):
  REDIS.DEL(f"stock:{sku_id}")

Why outside transaction?
- Redis DELETE can fail (brief window)
- Don't want to rollback DB transaction due to cache failure
- Cache failure acceptable (fallback to DB, slower but correct)

Fallback behavior:
- If cache delete fails: Entry has 5-second TTL
- Users hit cache → get stale value
- Some attempt reservation with stale data
- During checkout, fresh data from DB prevents double-sell
- User sees error (stale cache) and retries
- Acceptable: Brief window, P99 only

Optimal case:
- Invalidation succeeds
- Next read gets fresh data
- No staleness visible to user
```

#### Cost Analysis

```
Redis Cluster costs (AWS ElastiCache):
- Node type: cache.r7g.large (16GB, 100k RPS)
- Cost: $0.289/hour per node
- Cluster: 3 master + 3 slave = 6 nodes
- Cost: $0.289 × 6 × 30 min / 60 = $0.87

Event cost:
- Duration: 30 minutes
- Nodes: 6
- Total: $0.87 (very cheap)

vs. DynamoDB:
- On-demand: $0.25 per million RPS
- 47.5k RPS × 1800 seconds = 85.5 million RPS-seconds
- Cost: 85.5 × $0.25 / 1M = $0.02 (wait, that's wrong)

Actually DynamoDB pricing is complex:
- Write capacity units: 1 WCU = 1 write/sec (strongly consistent)
- 47.5k WCU needed
- Cost: 47.5k × $0.47 / hour = $22,325/hour
- 30 minutes = $11,162

Redis is 100x cheaper than DynamoDB!

Why so expensive? DynamoDB not designed for high-throughput writes.
Good for: Occasional reads/writes with automatic scaling
Bad for: 25k+ RPS sustained
```

---

### Decision 4: Database Consistency Model

#### CAP Theorem Analysis

```
CAP Theorem: Cannot have all three simultaneously
- Consistency (C): All nodes see same data
- Availability (A): System always responds
- Partition tolerance (P): System survives network splits

Flash Sale requirements:
- MUST have C (zero oversell = strong consistency)
- MUST have A (cannot reject orders, only rate limit)
- MUST have P (multi-region could have network issues)

Problem: CAP says "pick 2"
Reality: In presence of partition, must choose between:
  1. Consistency + Partition (AP fails): Reject requests (breaks requirement)
  2. Availability + Partition (CP fails): Stale reads possible (oversell risk)

Resolution: This system is SINGLE REGION (no partition)
- All critical inventory operations in one region
- Multi-region is disaster recovery, not active-active
- If network partition: Entire region down (unrecoverable anyway)
- Therefore: Can achieve CA + P (in non-partitioned state)

Database choice: PostgreSQL (consistency focus)
- Excellent ACID compliance
- Proven at scale (GitHub, Shopify)
- Strong consistency by default
- Multi-version concurrency control (MVCC) for high concurrency
```

#### Decision Tree

```
Question: Database consistency model for inventory?

├─ Option 1: Eventually Consistent (Cassandra, DynamoDB)
│  ├─ How it works:
│  │  ├─ Write to local node immediately (fast)
│  │  ├─ Replicate to other nodes asynchronously
│  │  ├─ All nodes eventually see same data
│  │  └─ During replication window: Inconsistent state
│  │
│  ├─ Example scenario:
│  │  ├─ Inventory: 10,000 units
│  │  ├─ Reservation 1: Reserve unit → local node updated
│  │  ├─ Reservation 2: Check availability → might see old value
│  │  ├─ Both get reservation (only 9,999 units exist)
│  │  └─ OVERSELL ✗
│  │
│  ├─ Cons:
│  │  ├─ Inconsistency window possible
│  │  ├─ Hard to prevent oversell
│  │  ├─ Requires application-level conflict resolution
│  │  ├─ Cannot meet zero oversell requirement
│  │  └─ Risk unacceptable for financial transactions
│  │
│  └─ REJECTED: Risk of oversell unacceptable
│
├─ Option 2: Strong Consistency (PostgreSQL)
│  ├─ Single primary database architecture
│  ├─ All writes go to primary
│  ├─ Replicas eventually consistent with primary
│  │
│  ├─ Characteristics:
│  │  ├─ Write latency: Higher (must go through primary)
│  │  ├─ Read latency: Can read from replica (fast)
│  │  ├─ Consistency: Guaranteed for all reads from primary
│  │  ├─ Transactional: Full ACID support
│  │  └─ Oversell: Prevented by transaction semantics
│  │
│  ├─ Pros:
│  │  ├─ Guarantees zero oversell
│  │  ├─ ACID transactions prevent data loss
│  │  ├─ Well-understood and proven
│  │  ├─ Mature tooling and support
│  │  └─ Standard in financial systems
│  │
│  ├─ Cons:
│  │  ├─ Primary is write bottleneck
│  │  ├─ Cannot scale writes horizontally
│  │  ├─ Failover required if primary dies (downtime)
│  │  └─ Higher latency for single writes
│  │
│  ├─ Mitigation:
│  │  ├─ Single-writer pattern (Kafka) reduces write frequency
│  │  ├─ Batch updates (250 reservations per DB write)
│  │  ├─ Automatic failover (physical replication + sentinel)
│  │  └─ Connection pooling (reduce overhead)
│  │
│  └─ SELECTED: Only option ensuring zero oversell
│
├─ Option 3: Hybrid (Quorum-based)
│  ├─ Concept: Write to 2/3 nodes, read from 2/3 nodes
│  ├─ Ensures consistency while maintaining availability
│  │
│  ├─ Implementation: Spanner, CockroachDB
│  ├─ Pros:
│  │  ├─ Distributed consistency without primary
│  │  ├─ High availability
│  │  ├─ Transparent failover
│  │  └─ Strong consistency guarantees
│  │
│  ├─ Cons:
│  │  ├─ Latency: Quorum read/write = slowest node in quorum
│  │  ├─ Network overhead: Multiple nodes must respond
│  │  ├─ Operational complexity: 5-7 nodes minimum for HA
│  │  ├─ Cost: Premium pricing (CockroachDB: $300+/month)
│  │  └─ Learning curve: New paradigm vs. traditional SQL
│  │
│  ├─ When good:
│  │  ├─ Multi-region active-active required
│  │  ├─ No single region acceptable
│  │  ├─ Global consistency critical
│  │  └─ Cost not constraint
│  │
│  └─ REJECTED: Overkill for single-region, higher latency
│
└─ Option 4: Temporal Consistency (Versioned snapshots)
   ├─ Keep multiple versions of each row
   ├─ Queries see snapshot from specific timestamp
   ├─ Prevents lost updates via snapshot isolation
   │
   ├─ Pros:
   │  ├─ Read doesn't block writes
   │  ├─ Writes don't block reads
   │  └─ Good concurrency
   │
   ├─ Cons:
   │  ├─ Complex query semantics
   │  ├─ Still need locks for updates (same as PostgreSQL MVCC)
   │  ├─ Not actually better than PostgreSQL
   │  └─ More operational overhead
   │
   └─ REJECTED: PostgreSQL already does this (MVCC)
```

#### Selected Solution: PostgreSQL Primary + Async Replicas

**Architecture**:
```
┌──────────────────────────────────┐
│   Transactional Operations       │
│   (Inventory, Reservations)      │
└──────────────┬───────────────────┘
               │ (Strong consistency)
        ┌──────v──────────┐
        │  PostgreSQL     │
        │  Primary        │
        │  (Read + Write) │
        └──────┬──────────┘
               │ (Async replication)
    ┌──────────┼──────────┐
    │          │          │
    v          v          v
┌────────┐ ┌────────┐ ┌────────┐
│Replica │ │Replica │ │Replica │
│(US-2)  │ │(EU-1)  │ │(APAC-1)│
└────────┘ └────────┘ └────────┘
    │          │          │
    └──────────┼──────────┘
               │
        Analytics queries
        (eventual consistency OK)

Replication lag: 10-100ms typical
```

**Transactional Data** (Inventory, Reservations, Orders):
```
Table: inventory
├─ sku_id (PK)
├─ reserved_count (atomic counter)
├─ sold_count
├─ created_at, updated_at
└─ version (optimistic locking for safety)

Table: reservations
├─ reservation_id (PK, UUID)
├─ user_id (FK)
├─ sku_id (FK)
├─ status (RESERVED, EXPIRED, CONFIRMED, FAILED)
├─ expires_at (T + 2 minutes)
├─ idempotency_key (UNIQUE)
├─ created_at
└─ Indexes: (user_id, status), (sku_id), (idempotency_key)

Table: orders
├─ order_id (PK, UUID)
├─ user_id (FK)
├─ sku_id (FK)
├─ reservation_id (FK)
├─ status (PENDING, PAID, FAILED)
├─ idempotency_key (UNIQUE)
├─ created_at, paid_at
└─ Indexes: (user_id), (sku_id), (idempotency_key)
```

**Analytical Data** (Eventual consistency acceptable):
```
Read replicas used for:
├─ Reports and dashboards
├─ Analytics queries
├─ Audit log analysis
├─ User statistics

Lag is acceptable (10-100ms):
├─ Not user-facing (internal only)
├─ Data science queries
├─ Historical analysis
└─ Reconciliation reports
```

**Write Path** (Transactional):
```
1. Single-writer Kafka consumer gets batch of 250 requests
2. Acquires lock on inventory row (pessimistic or optimistic)
3. Validates all 250 requests
4. Builds transaction:
   BEGIN
     UPDATE inventory SET reserved_count = reserved_count + 250
     INSERT INTO reservations (batch) VALUES (...)
   COMMIT
5. On success: Publish events, invalidate cache
6. On failure: Rollback, retry

Latency:
- Lock acquisition: <1ms
- Validation: 2ms
- DB write: 5-10ms
- Total: ~10-15ms per batch
- Per-request: 10-15ms / 250 = 40-60 microseconds
```

**Read Path** (Analytical from replicas):
```
For analytics (non-critical):
1. Route queries to read replica
2. May see 10-100ms old data
3. For analytics this is acceptable

For user-facing reads:
1. Availability cache in Redis (preferred)
2. If cache miss: Query replica (acceptable lag)
3. If replica lag > 100ms: Query primary (slower but consistent)
```

---

### Decision 5: Rate Limiting & Fair Queuing

#### Attack Scenarios

**Scenario 1: Simple Bot (Single IP, High RPS)**
```
Attacker: Run 1000 requests/second from single bot
Without rate limiting:
- Bot gets 1000 requests in queue
- Legitimate users get 24k requests in queue
- Bot has ~4% of queue (should be 1 user = 1/millions)
- Unfair advantage

With per-IP rate limiting (100 req/min = 1.67 req/sec):
- Bot request 1: Allowed (quota available)
- Bot request 2: Rate limited
- Bot requests 3-1000: Rate limited
- Bot gets 1 slot, legitimate user gets 1 slot
- FAIR ✓
```

**Scenario 2: Distributed Bot (Multiple IPs)**
```
Attacker: 1000 bot instances, each from different IP
Total: 25,000 requests/second from 1000 different IPs
- Looks like 1000 legitimate users
- Cannot detect by IP alone

Detection methods:
1. Device fingerprinting:
   - All 1000 bots have same User-Agent: "Mozilla/5.0 Bot"
   - All have same Accept-Language: "en-US"
   - All have same Accept-Encoding
   - Fingerprint collision → Rate limit

2. Behavioral analysis:
   - Real users: Make 1-5 requests over 30 seconds
   - Bots: Make 100+ requests in 1 second
   - Real users: Browse product, read description, then reserve
   - Bots: Straight to reserve endpoint
   - Bots have 0 think time, humans have 1-5 second think time

3. Time-based patterns:
   - Real user: Request at T=0, T=0.5s, T=2s (irregular)
   - Bot: Request at T=0, T=0.1s, T=0.2s (regular intervals)

4. Captcha/2FA:
   - Suspicious accounts must verify before reserving
   - Real users: Can solve, proceed
   - Bots: Cannot solve, blocked

Implementation:
```python
# Tier assignment logic
def get_user_tier(user_id, ip, device_fingerprint, request_history):
    risk_score = 0

    # Device fingerprinting check
    if device_fingerprint == "bot_pattern":
        risk_score += 50

    # Behavioral check
    if request_rate_last_second > 100:  # Rapid requests
        risk_score += 40

    if request_history.zero_think_time:  # No pause between requests
        risk_score += 30

    # IP reputation
    if ip in vpn_list:
        risk_score += 20

    if ip in blacklist:
        risk_score += 100

    # Assign tier
    if risk_score > 80:
        return TIER_1  # 1 req/min
    elif risk_score > 50:
        return TIER_2  # 50 req/min
    else:
        return TIER_3  # 100 req/min
```

#### Decision Tree

```
Question: How to rate limit 25k RPS while remaining fair?

├─ Option 1: No rate limiting
│  ├─ Approach: Accept all requests, process as fast as possible
│  ├─ Problem: Bots get equal queuing as humans
│  │  ├─ Bot making 1000 req/sec gets 1000 queue slots
│  │  ├─ Humans making 1 req get 1 queue slot
│  │  ├─ Bot wins lottery (more slots = more chances)
│  │  ├─ Unfair to honest users
│  │  └─ Likely 99% of queue is bot traffic
│  ├─ Additional problem: Queue grows unbounded
│  │  ├─ Memory for queue grows indefinitely
│  │  ├─ Eventually: Out of memory, crash
│  │  └─ Denial of service possible
│  └─ REJECTED: Unfair, causes system collapse
│
├─ Option 2: Simple Per-IP Rate Limit
│  ├─ Approach: 100 requests per minute per IP
│  ├─ Mechanism:
│  │  ├─ Track request count per IP in Redis
│  │  ├─ Reset counter every minute
│  │  ├─ Reject request if count > 100
│  │  └─ Return HTTP 429 (Too Many Requests)
│  │
│  ├─ Pros:
│  │  ├─ Simple implementation
│  │  ├─ Effective against single-IP bots
│  │  ├─ Low operational overhead
│  │  └─ Standard approach (used by GitHub, Twitter)
│  │
│  ├─ Cons:
│  │  ├─ Ineffective against distributed bots (1000 IPs)
│  │  ├─ ISP users: Shared IP = rate limited together
│  │  │  ├─ University: 10,000 students sharing proxy
│  │  │  ├─ All share same IP
│  │  │  ├─ Quota = 100 req/min for entire university
│  │  │  └─ Very unfair to legitimate users
│  │  ├─ Cannot distinguish between bot and human
│  │  ├─ No fairness guarantee (first-come wins)
│  │  └─ Does not prevent queue from growing
│  │
│  └─ REJECTED: Insufficient against sophisticated bots
│
├─ Option 3: Multi-dimensional Rate Limiting
│  ├─ Approach: Rate limit by IP + User ID + Device
│  ├─ Per-user limit: 100 requests/minute
│  ├─ Per-IP limit: 1000 requests/minute (covers shared IPs)
│  ├─ Per-device limit: 100 requests/minute
│  │
│  ├─ Advantages:
│  │  ├─ Catches more attack patterns
│  │  ├─ Handles shared IPs better
│  │  ├─ Device fingerprinting adds bot detection
│  │  └─ More nuanced than single dimension
│  │
│  ├─ Cons:
│  │  ├─ More complex (3 dimensions to track)
│  │  ├─ False positives (real users marked as bots)
│  │  ├─ Still doesn't guarantee fairness in queue
│  │  └─ Users still rejected even if quota available
│  │
│  └─ Good but not sufficient alone
│
├─ Option 4: Token Bucket + FIFO Queue (Choreography)
│  ├─ Approach:
│  │  ├─ Each user gets token budget per second
│  │  ├─ Request uses token
│  │  ├─ Tokens replenish each second
│  │  ├─ No tokens? → Queue (FIFO)
│  │  └─ Dequeue when token available
│  │
│  ├─ Token allocation:
│  │  ├─ Tier 1: 1 token/min
│  │  ├─ Tier 2: 50 tokens/min
│  │  ├─ Tier 3: 100 tokens/min
│  │  ├─ Tier 4: 200 tokens/min
│  │
│  ├─ Queue semantics:
│  │  ├─ User makes 101 requests (tier 3 = 100/min quota)
│  │  ├─ First 100: Allowed (tokens available)
│  │  ├─ Request 101: Queued (no tokens)
│  │  ├─ Tokens replenish next second: 100 new tokens
│  │  ├─ Dequeue request 101: Allowed
│  │  └─ User waits ~1 second (fair)
│  │
│  ├─ Fair distribution:
│  │  ├─ All users in queue served FIFO
│  │  ├─ User who queued first gets served first
│  │  ├─ Speed doesn't matter (no advantage to retries)
│  │  ├─ Honest users: ~1 token/sec
│  │  ├─ Bots: Same ~1 token/sec
│  │  └─ FAIR ✓
│  │
│  ├─ Pros:
│  │  ├─ Fair FIFO queue (main advantage)
│  │  ├─ Allows initial burst (first 100 requests)
│  │  ├─ Bots cannot "win" by retrying fast
│  │  ├─ Queue is bounded (max = (25k RPS × queue_window))
│  │  ├─ Prevents system collapse
│  │  └─ Users know they'll eventually get service
│  │
│  ├─ Cons:
│  │  ├─ Complex implementation (queue + token logic)
│  │  ├─ Queue storage cost (memory/Redis)
│  │  ├─ Queueing latency (wait time in queue)
│  │  ├─ Clients see 429 + queue position
│  │  └─ Not instant gratification
│  │
│  ├─ Failure modes:
│  │  ├─ Queue fills up (memory pressure)
│  │  │  └─ Solution: Reject new requests with 503, suggest retry later
│  │  ├─ Token replenishment fails (Redis down)
│  │  │  └─ Solution: Graceful degradation, reject to be safe
│  │  └─ Out-of-order processing
│  │      └─ Solution: Use Kafka for ordering guarantees
│  │
│  └─ SELECTED: Best option for fairness and user experience
│
├─ Option 5: Leaky Bucket
│  ├─ Concept: Fixed rate drain (constant output)
│  ├─ Problem for flash sales:
│  │  ├─ Initial burst rejected (no burst allowed)
│  │  ├─ Users waiting unnecessarily (constant rate only)
│  │  ├─ Good for streaming, bad for flash sales
│  │  └─ Legitimate users blocked at peak
│  └─ REJECTED: Too restrictive for flash sale
│
└─ Option 6: Sliding Window Counter
   ├─ Concept: Count requests in rolling window
   ├─ Last 60 seconds: Track all requests in window
   ├─ New request: Check if count > quota
   ├─ Problem: More accurate but complex
   │  ├─ Memory: O(n) per user (track all request times)
   │  ├─ Latency: O(n) lookup per request
   │  ├─ At 25k RPS, each user might have 100 requests in window
   │  ├─ Lookup cost: 100 × 25k = 2.5M operations/sec
   │  └─ Impractical
   └─ REJECTED: Too expensive for high throughput
```

#### Selected Solution: Token Bucket + FIFO Queue + Tiered Assignment

**Implementation Architecture**:
```
┌────────────────────────────────────┐
│     Incoming Reservation Request    │
└──────────────┬─────────────────────┘
               │
        ┌──────v──────────────┐
        │ Determine User Tier │
        │ (based on history)  │
        └──────┬───────────────┘
               │
        ┌──────v──────────────┐
        │  Check Token Bucket │
        │ (tokens available?) │
        └──────┬───────────────┘
               │
        ┌──────┴──────┐
        │             │
    YES v             v NO
   ┌─────┐     ┌──────────────┐
   │Allow│     │ Enqueue FIFO │
   └─────┘     │ Return 429   │
               │ + queue_pos  │
               └──────────────┘

When token becomes available:
  ├─ Dequeue oldest request
  ├─ Process normally
  └─ Send notification

Token replenishment:
  Every 1 second:
  ├─ Tier 1: Grant 1 token
  ├─ Tier 2: Grant 50 tokens
  ├─ Tier 3: Grant 100 tokens
  ├─ Tier 4: Grant 200 tokens
  └─ Cap tokens at max (prevent hoarding)
```

**Redis Implementation**:
```
# Token bucket for user
key: "quota:{user_id}:{tier}"
value: {
  "tokens": 100,           // Current tokens available
  "last_refill": 1705325645,  // Unix timestamp
  "tier": "TIER_3"
}

# Fair queue
key: "queue:{sku_id}"
value: [
  {user_id: "u1", created_at: 1705325645000, priority: 0},
  {user_id: "u2", created_at: 1705325645100, priority: 0},
  ...
]

# Queue position for client feedback
key: "queue_position:{user_id}:{sku_id}"
value: 12345  // Position in queue

Pseudocode:

def reserve(user_id, sku_id, idempotency_key):
    tier = get_user_tier(user_id)

    # Check token bucket
    quota_key = f"quota:{user_id}:{tier}"
    current_tokens = redis.get(quota_key)

    if current_tokens > 0:
        # Have tokens, proceed immediately
        redis.decr(quota_key)
        return process_reservation(user_id, sku_id, idempotency_key)
    else:
        # No tokens, add to queue
        queue_key = f"queue:{sku_id}"
        queue_entry = {
            user_id: user_id,
            created_at: now(),
            idempotency_key: idempotency_key
        }
        queue_pos = redis.lpush(queue_key, json.dumps(queue_entry))

        return {
            status: "QUEUED",
            queue_position: queue_pos,
            message: f"Wait your turn. Position: {queue_pos}"
        }

# Background job: Token replenishment
every 1 second:
    for each user_id in Redis:
        tier = get_user_tier(user_id)
        max_tokens = tier_limits[tier]
        quota_key = f"quota:{user_id}:{tier}"

        redis.set(quota_key, max_tokens)

        # Dequeue if tokens available
        queue_key = f"queue:{sku_id}"
        while redis.llen(queue_key) > 0 and tokens_available:
            entry = redis.rpop(queue_key)  // FIFO: oldest first
            // Schedule processing
            process_queued_reservation(entry)
            tokens_available -= 1
```

---

## Risk Analysis for Each Decision

### Critical Risk 1: Oversell Detection and Recovery

**Risk**: Despite best efforts, oversell occurs (audit log shows sold > 10,000)

**Likelihood**: Very low (~0.01%) if single-writer correctly implemented
**Impact**: Very high (legal liability, customer refunds, reputation)

**Detection**:
```
Real-time monitoring:
- Metric: oversell_count = max(0, sold_count - total_stock)
- Alert: IF oversell_count > 0 → IMMEDIATE page on-call
- Frequency: Check every 10 seconds

Detection latency: ~10 seconds after oversell occurs
```

**Root Causes**:
```
1. Code bug in batch allocation logic
   └─ Scenario: Count off-by-one error, allocate 251 instead of 250

2. Database corruption
   └─ Scenario: Disk corruption causes inventory row corruption

3. Race condition in non-single-writer path
   └─ Scenario: Legacy code path bypasses Kafka queue

4. Admin error
   └─ Scenario: Manual SQL UPDATE without locking

5. Concurrent independent consumers
   └─ Scenario: Two consumer instances both processing
```

**Recovery Procedures**:
```
When oversell detected:

Step 1: Immediate containment (T=0)
- STOP accepting new reservations
- Return HTTP 503 to clients
- Pause checkpoint for pending orders

Step 2: Human investigation (T=1-5min)
- Determine which orders to refund
- Check: Did we actually receive payment?
- Orders to refund: Last N orders (most recent oversell)

Step 3: Refunds (T=5-30min)
- Initiate refund API calls to payment processor
- Send notification emails to affected customers
- Issue credit/voucher for inconvenience

Step 4: Root cause analysis (T=30min+)
- Review code changes since last event
- Check system logs for anomalies
- Verify single-writer implementation
- Check for bug in batch allocation

Step 5: Prevention (T>1hour)
- Fix root cause bug
- Deploy fix with QA testing
- Restart system if needed
- Resume sales only after verification
```

**Cost of oversell**:
```
Per oversold unit:
- Refund cost: $100-500 (product price)
- Processing cost: $10 (refund fee + ops)
- Customer compensation: $50 (goodwill, credit)
- Reputation damage: Unmeasurable (trust loss)

Oversell 100 units:
- Direct cost: $16,000
- Reputation damage: Could lose market share

Prevention investment worth much more than recovery cost
```

---

### Critical Risk 2: Single Point of Failure in Inventory Consumer

**Risk**: Kafka consumer crashes, inventory updates stop, users think system is down

**Scenario**:
```
T=0: Consumer processing batch of 250 requests
T=10ms: Database write fails due to timeout
T=20ms: Consumer crashes on exception
T=21ms: Kafka offset not committed
T=22ms: New consumer instance starts
T=23ms: Consumer replays previous batch (reprocessing)
T=30ms: Duplicate reservations detected by idempotency key

Actually, this is okay!
- Idempotency keys prevent double-reservation
- Users get consistent responses
- System recovers automatically
```

**Mitigation**:
```
1. Multiple consumer instances (3 replicas)
   ├─ Primary processes, offset committed
   ├─ Secondary on standby
   ├─ If primary crashes: Secondary takes over
   ├─ Kafka rebalancing: <10 seconds
   └─ No data loss due to idempotency

2. Graceful shutdown
   ├─ On SIGTERM: Finish current batch
   ├─ Commit offset
   ├─ Flush in-flight events
   ├─ Then exit

3. Circuit breaker on database
   ├─ If DB timeout: Exponential backoff
   ├─ Retry up to 10 times
   ├─ If still failing: Crash explicitly (trigger failover)
   └─ Don't silently fail

4. Health checks
   ├─ Liveness: Is consumer running? (restart if dead)
   ├─ Readiness: Is consumer ready? (wait for warmup)
   ├─ Specific: Last batch processed < 30 seconds ago
   └─ If failed: Kubernetes kills + restarts pod
```

---

### Critical Risk 3: Cache Invalidation Failures

**Risk**: Cache invalidation fails (Redis down), users see stale stock count, oversell occurs

**Scenario**:
```
T=0: Reservation succeeds, stock = 9,999
T=1ms: Cache invalidation sent to Redis
T=2ms: Redis crashed mid-request
T=3ms: Cache still has stock = 10,000
T=4ms: User checks availability, sees 10,000 (stale)
T=5ms: User reserves another unit → OVERSELL

Wait, does this actually cause oversell?
```

**Analysis**:
```
Assumption: Cache miss → Query database for truth
User sees stale cache: stock = 10,000
User tries to reserve: Calls database check
Database truth: stock = 9,999
Database protects against oversell
User gets error: "Out of stock"
No oversell! ✓

Risk only if:
1. Cache is authoritative (never consult DB)
2. User assumes cache is truth
3. No fallback check against DB

In our design:
- Cache is optimization, not authority
- Database is authority
- User can ignore cache error and retry
- System is safe even if cache entirely broken
```

**Mitigation**:
```
1. Cache failure tolerance:
   ├─ If Redis unavailable: Fall back to DB
   ├─ Slower (DB latency), but correct
   ├─ Users experience slowdown, not oversell

2. TTL safety net:
   ├─ Cache entries expire after 5 seconds
   ├─ Stale data window: max 5 seconds
   ├─ Then forced refresh from database

3. Alerting:
   ├─ Alert if Redis unavailable
   ├─ Alert if cache hit rate drops <80%
   ├─ Page on-call to investigate

4. Dual invalidation:
   ├─ Redis DEL immediate
   ├─ If fails: Retry with exponential backoff
   ├─ Eventually consistent recovery
```

---

## Failure Mode Analysis

### Failure Mode 1: Database Connection Pool Exhaustion

**Cause**:
```
Batch consumer holds connection during 10ms processing:
- 25k RPS / 250 batch = 100 batches/second
- Each batch: 1 connection × 10ms
- Concurrency: 100 batches/sec × 0.01s = 1 concurrent connection

Wait, that's fine! Only 1 connection needed.

But what about read replicas?
- Analytics queries (50 connections)
- Audit log queries (20 connections)
- Total: 71 connections

PostgreSQL default max_connections: 100
Overhead (system): 5 connections
Available for app: 95 connections
Usage: 71 connections
Headroom: 24 connections ✓

Unless queries slow down:
- Slow query takes 100ms instead of 10ms
- Concurrency: 100 × 0.1s = 10 connections instead of 1
- Total: 50 + 20 + 10 = 80 connections (still okay)

But under load:
- Disk I/O gets slow
- Queries block longer
- Concurrency grows
- Could hit 95 limit → Rejection

If hit limit:
- New queries wait in pool queue
- Eventually timeout (30s default)
- Error returned to client
- For inventory: Circuit breaker triggers
- For analytics: Query fails, retry
```

**Mitigation**:
```
1. Connection pooling (HikariCP):
   ├─ Max connections: 20 per app instance
   ├─ Min connections: 5 (keep warm)
   ├─ Wait timeout: 30 seconds
   ├─ Connection timeout: 10 seconds
   └─ Idle timeout: 10 minutes

2. Database tuning:
   ├─ max_connections: 200 (allow headroom)
   ├─ Shared buffers: 25% RAM
   ├─ Effective cache size: 50% RAM
   ├─ Random page cost: 1.1 (SSD)
   └─ Maintenance work_mem: 64MB

3. Query optimization:
   ├─ Add indexes on frequently queried columns
   ├─ Analyze query plans
   ├─ Cache heavy queries
   └─ Use partitioning if tables grow large

4. Monitoring:
   ├─ Alert if active_connections > 80
   ├─ Alert if queue_wait > 10s
   ├─ Graph database latency by query type
   └─ Identify slow queries (> 100ms)
```

---

### Failure Mode 2: Kafka Consumer Lag Growing

**Cause**:
```
Consumer cannot keep up with producer:
- Producer: 25k messages/second to queue
- Consumer batch: 250 messages every 10ms = 25k/sec
- Capacity: Equal, should be fine

But under real conditions:
- Batch processing takes 15ms instead of 10ms
- Slack: 25k × (15-10)/1000 = 125 messages/sec backlog
- After 1 minute: 7,500 messages behind
- After 5 minutes: 37,500 messages behind
- System feels slow to users (queue backlog grows)

Root causes:
1. Database is slow (taking 15ms instead of 5ms)
2. Lock contention (waiting for lock)
3. Garbage collection pause (stop-the-world GC)
4. Network latency to database
5. Disk I/O bottleneck
```

**Symptoms**:
```
- Client sees 429 (rate limited) more often
- Queue position growing
- P95 latency increasing
- Users report slow reservations
```

**Mitigation**:
```
1. Real-time monitoring:
   ├─ Metric: consumer_lag (lag in messages)
   ├─ Alert if lag > 10,000
   ├─ Trend alert if lag growing continuously
   └─ Root cause analysis required

2. Scaling:
   ├─ Cannot add more consumers (single partition)
   ├─ Can only increase batch size
   ├─ Alternative: Pre-fetch into memory, batch larger
   └─ Max batch: 1000 (diminishing returns)

3. Optimization:
   ├─ Profile slow batch processing
   ├─ Check database query latency
   ├─ Verify no lock contention
   ├─ Tune JVM GC (use ZGC for <10ms pauses)
   └─ Optimize batch validation logic

4. Graceful degradation:
   ├─ If lag > 100k: Stop accepting new requests
   ├─ Return 503 "Service Temporarily Unavailable"
   ├─ Suggest users retry in 10 minutes
   └─ Prevents queue from growing indefinitely
```

---

## Operational Implications

### Pre-Event Operations (T-1 hour)

**1. Infrastructure Setup**:
```
Compute:
├─ Kubernetes cluster scaled to 50% capacity
├─ All node pools healthy
├─ Metrics collection enabled
├─ Logging aggregation active
└─ Alerts configured and tested

Database:
├─ Primary database online
├─ Read replicas synchronized (replication lag < 50ms)
├─ Backup fresh (within 1 hour)
├─ Connection pooling tested
└─ Slow query log enabled for analysis

Cache:
├─ Redis cluster started (3 masters + 3 slaves)
├─ Cluster health check passed
├─ Memory available: 3x product data size
├─ Pub/Sub channels ready
└─ Eviction policy: No eviction (error on OOM)

Message Queue:
├─ Kafka brokers running
├─ Topic created with 1 partition (SKU ordering)
├─ Replication factor: 3 (HA)
├─ Retention: 24 hours
├─ Compaction: Disabled for this topic
└─ Consumer group reset to beginning (clean slate)
```

**2. Data Preparation**:
```
Product Data:
├─ Product metadata loaded into Redis
├─ CDN caches warm with latest images
├─ Availability counts in cache match database
├─ Search indexes refreshed
└─ Verify no stale data

Inventory:
├─ Inventory row exists: sku_id, total_stock=10000, reserved=0, sold=0
├─ No existing reservations for this SKU
├─ No existing orders for this SKU
├─ Audit log cleared or archived
└─ Verify state with query

User Data:
├─ User tiers pre-computed (verified users = Tier 4)
├─ Watchlist users notified (email sent)
├─ Rate limit state cleared (start fresh)
└─ Session cache cleared

Monitoring:
├─ Dashboard created (key metrics visible)
├─ Alerting rules validated
├─ On-call pager verified (test alert sent)
├─ Slack/Teams integration tested
└─ War room channel opened
```

**3. Capacity Planning**:
```
Compute scaling readiness:
├─ Horizontal pod autoscaler enabled
├─ Min replicas: 10 per service
├─ Max replicas: 200 per service
├─ Scale-up trigger: CPU > 70%, requests queued > 100
├─ Scale-down: CPU < 20% for 5 minutes
└─ Scaling cooldown: 1 minute between scaling events

Database readiness:
├─ Connection pool: 50% utilized (headroom available)
├─ Query cache populated
├─ Replication healthy (0 lag)
├─ Failover tested in staging (not production)
└─ Backup completed < 1 hour ago

Network readiness:
├─ CDN origins ready to handle cache misses
├─ API gateway instances warm (no cold start)
├─ Load balancer health checks passing
├─ BGP routes advertised
└─ DDoS mitigation enabled at Cloudflare
```

**4. Team Preparation**:
```
On-Call Team:
├─ Primary: Engineer A (on laptop, ready for escalations)
├─ Secondary: Engineer B (on standby, monitoring)
├─ Manager: Ready to authorize emergency measures
├─ Communications: Dedicated person for customer updates
└─ All contacts verified working

Runbooks prepared:
├─ Oversell detection procedure
├─ Database failover steps
├─ Cache flush + warm procedure
├─ Consumer restart procedure
├─ Emergency scale-down (stop accepting)
└─ Post-event analysis checklist
```

### During-Event Operations (T0 to T0+5 min)

**T0 - 30 seconds: Pre-warming**
```
Actions:
1. Scale up Kubernetes pods
   ├─ API gateway: 10 → 50 replicas
   ├─ Reservation service: 5 → 30 replicas
   ├─ Checkout service: 3 → 20 replicas
   └─ Wait for readiness (120 second startup time)

2. Warm caches
   ├─ Pre-populate Redis with product data
   ├─ Hit database a few times to warm query cache
   ├─ Prime CDN edge caches
   └─ Verify <5ms latency for reads

3. Health checks
   ├─ All pod readiness probes passing
   ├─ Database replication lag < 50ms
   ├─ Kafka consumer ready (no lag)
   ├─ Redis cluster healthy
   └─ Metrics collection active

Monitoring:
├─ Watch pod startup times (should be <2 seconds)
├─ Verify no errors in application logs
├─ Check database connection pool (should be empty)
├─ Confirm alerting is active
└─ Verify war room channel is monitoring

If issues detected:
├─ Delay announcement (push back T0 by 30 minutes)
├─ Investigate and fix
├─ Repeat pre-warming
└─ Do not proceed until fully ready
```

**T0 to T0+3 seconds: Initial Spike**
```
Expected behavior:
├─ Requests start arriving at 1k RPS
├─ Ramps to 25k RPS within 3 seconds
├─ Queue builds from 0 to peak
└─ Cache hit rate >99% (warm cache)

Monitoring actions:
├─ Watch request rate (increase expected)
├─ Monitor latency percentiles (should be stable)
├─ Check error rate (should be <0.1%)
├─ Track oversell_count (must be 0)
├─ Monitor queue depth (acceptable < 100k)
└─ Verify auto-scaling triggers (pods should be starting)

If latency SLOs violated:
├─ Check: Is it a gradual increase or sudden?
├─ Gradual: System handling spike, monitor closely
├─ Sudden: Possible cascading failure
│  ├─ Check database latency (query slow?)
│  ├─ Check memory (GC pauses?)
│  ├─ Check network (congestion?)
│  └─ If not obvious: Enable detailed tracing

If queue growing:
├─ Check: Consumer lag (is consumer keeping up?)
├─ If lag zero: Queue is from backlog, expected
├─ If lag growing: Consumer falling behind
│  ├─ Check batch processing time
│  ├─ Verify database responding
│  ├─ Look for lock contention
│  └─ May need to increase batch size
```

**T0+3 to T0+5 seconds: Sustained Load**
```
Expected behavior:
├─ Traffic plateaus at 25k RPS
├─ Queue depth stabilizes
├─ Cache continues >99% hit rate
├─ Latency remains consistent
└─ Stock depleting at constant rate (400 units/sec)

Key monitoring points:
├─ Stock depletion rate: 400 units/second
│  ├─ 10,000 units / 25 seconds = end at T+25sec
│  ├─ If slower: Possible rejection rate issue
│  ├─ If faster: Possible double-counting (oversell risk)
│  └─ Verify actual with: SELECT sold_count FROM inventory
│
├─ User fairness metric
│  ├─ Distribution of reservations across users
│  ├─ Should be even (each user gets ~1 reservation)
│  ├─ If skewed: Indicates bot traffic succeeding
│  └─ Investigate tier assignments
│
├─ Payment processing
│  ├─ Checkout requests arriving (5% of total RPS)
│  ├─ Payment processing latency
│  ├─ Success rate (should be >95%)
│  └─ If errors: Contact payment processor
│
└─ Error tracking
   ├─ Rate limit errors (HTTP 429): Expected for unprivileged users
   ├─ Database errors: Should be 0
   ├─ Cache errors: Should be <1%
   └─ Any errors != 429: Investigate immediately
```

**T0+5 to T0+30 seconds: Stock Depletion**
```
Expected behavior:
├─ Stock depletes (25 seconds until 10,000 units gone)
├─ Reservation requests still arriving but rejected (out of stock)
├─ Users see: "This item is no longer available"
├─ Queued users get: "Out of stock, thank you for trying"
└─ Checkout continues for those who reserved

Monitoring:
├─ Stock count approaching zero
├─ Oversell metric remains 0 (critical)
├─ Reservation success rate dropping (more "out of stock" errors)
├─ Checkout requests increasing (reserved users checking out)
└─ Revenue tracking (how much did we sell?)

Actions:
├─ Announce sold out (update product page)
├─ Notify waiting users (email/SMS)
├─ Prepare post-event analysis
├─ Begin gradual scale-down (if desired)
└─ Monitor for spikes in checkout traffic
```

**T0+30 to T0+60 seconds: Wrap-up**
```
Objectives:
├─ Complete pending checkout requests
├─ Process remaining payment requests
├─ Generate immediate summary (units sold, revenue)
├─ Begin infrastructure cleanup
└─ Prepare reconciliation report

Actions:
├─ Stop accepting new reservations (return 503)
├─ Continue processing pending checkouts
├─ Scale down API gateway (reduce to 20 replicas)
├─ Scale down services (reduce by 50%)
├─ Announce sale is complete
└─ Monitor for issues during scale-down

Monitoring:
├─ Pending checkout count (target: 0 within 5 minutes)
├─ Payment processor queue (target: drain within 10 minutes)
├─ Any errors in pending operations
└─ Oversell metric (must still be 0)
```

---

## Cost Analysis

### Infrastructure Costs (30-minute event)

**Kubernetes Cluster**:
```
Node: 5 large nodes (8 vCPU, 32GB RAM)
Cost: $0.72/hour per node
Duration: 30 minutes (+ 30 min scale-up time) = 1 hour
Cost: 5 × $0.72 = $3.60

Pre-event scaling (T-1 hour):
Cost: $3.60 × 1 hour = $3.60
Total K8s: $7.20
```

**Database (PostgreSQL managed service)**:
```
Base cost: $0.50/hour (small instance)
High load surcharge: None (pay per usage, not per peak)
Backup storage: $0.02 (extra hourly snapshot)
Total DB: $0.52/hour × 1.5 hours = $0.78
```

**Cache (Redis cluster)**:
```
6 nodes × $0.289/hour = $1.73/hour
1 hour (pre-warming + event) = $1.73
```

**Load Balancer**:
```
AWS Network Load Balancer: $0.006/hour
Regional LBs: 3 × $0.006 = $0.018/hour
API Gateway: $0.035/million requests
25k RPS × 30 sec × 3 events = 75k requests
Cost: $0.035 × 75/1M = negligible

Total LB: ~$0.10
```

**CDN & DDoS Protection**:
```
Cloudflare: $20/month standard
Flash sale usage: Negligible
Pro-rata cost: $0.41 for 30 minutes
```

**Message Queue (Kafka)**:
```
AWS MSK managed: $0.076/broker-hour
3 brokers × 1.5 hours = $0.34
```

**Observability (Prometheus, Grafana, ELK)**:
```
Self-hosted on existing infrastructure: $0
Cloud-based (Datadog): $0.05/host per hour
5 hosts × 1.5 hours × $0.05 = $0.37
```

**Total Infrastructure Cost: ~$11-15 for 30-minute event**

### Operational Costs

**Engineering Time**:
```
Monitoring during event: 2 engineers × 1 hour = 2 engineer-hours
Post-event analysis: 1 engineer × 4 hours = 4 engineer-hours
Planning & pre-event setup: 2 engineers × 6 hours = 12 engineer-hours

Typical annual cost: Engineers @ $80/hour
Total for this event: 18 hours × $80 = $1,440
```

**Customer Acquisition Cost (CAC)**:
```
10,000 units sold at average $200 = $2M revenue
Net margin (assuming 50%): $1M profit
Successful user experience: Invaluable for repeat customers
```

---

## Team Skillset Requirements

### Core Skills Needed

**1. Backend Engineering (3-4 engineers)**
- Java/Spring Boot (or equivalent framework)
- Distributed systems concepts (CAP theorem, eventual consistency)
- Message queue experience (Kafka, RabbitMQ)
- Performance optimization and profiling
- Database tuning and optimization

**2. DevOps/Platform Engineering (2-3 engineers)**
- Kubernetes administration and troubleshooting
- Infrastructure as Code (Terraform, Helm)
- Monitoring and observability (Prometheus, Grafana, ELK)
- CI/CD pipeline design and implementation
- Database replication and failover procedures

**3. Database Engineering (1-2 engineers)**
- PostgreSQL expertise
- Query optimization and indexing
- Replication and backup strategies
- Connection pooling and tuning
- Disaster recovery procedures

**4. QA/Testing (1-2 engineers)**
- Load testing (JMeter, Gatling, K6)
- Chaos engineering (Gremlin, Chaos Monkey)
- Integration testing frameworks
- Stress testing under production-like load
- Failure scenario testing

**5. Data/Analytics (1 engineer)**
- Real-time metrics and alerting
- Audit log analysis
- Data reconciliation
- Dashboarding and visualization

### Knowledge Gaps to Address

**Distributed Systems**:
```
Not everyone understands:
- Idempotency and exactly-once semantics
- Saga pattern for distributed transactions
- CAP theorem trade-offs
- Event sourcing patterns

Solution:
- Organize internal training sessions
- Prepare documentation with visual diagrams
- Share relevant papers/articles
- Practice with smaller systems first
```

**High-Performance Engineering**:
```
Design decisions must be made with latency in mind:
- Every millisecond counts at 25k RPS
- Batch sizes, lock contention, network hops
- GC pauses, CPU cache misses, disk I/O

Solution:
- Performance profiling workshop
- Distributed tracing hands-on session
- Load testing in staging environment
- Metrics-driven optimization practice
```

**Operational Excellence**:
```
Running at scale requires:
- Clear runbooks for failure scenarios
- Practiced incident response procedures
- Monitoring and alerting discipline
- Post-incident review culture

Solution:
- Create detailed runbooks for all failure modes
- Practice failure scenarios in staging
- Run "war games" to test team readiness
- Document all decisions (this architecture document)
```

---

## Summary

This ultra-comprehensive architecture ensures:

1. **Zero oversell** through single-writer serialization
2. **Sub-150ms latencies** through aggressive caching and optimization
3. **Fair distribution** through token bucket + FIFO queue
4. **Complete auditability** through event sourcing and WORM logs
5. **High reliability** through distributed patterns and graceful degradation
6. **Operational clarity** through detailed decision documentation

Every architectural decision explicitly weighs:
- **Correctness** (does it prevent oversell?)
- **Performance** (does it meet latency SLOs?)
- **Reliability** (does it handle failures gracefully?)
- **Fairness** (does it prevent bot wins?)
- **Cost** (is it efficient?)
- **Complexity** (can the team maintain it?)

The system is designed to handle the extreme constraints of a flash sale while remaining maintainable and debuggable by a typical engineering team.
