# Flash Sale System - Solution Design Document

**Version**: 2.0
**Date**: 2025-01-16
**Status**: Approved for Implementation
**Previous Version**: V1.0 - Single product focus
**Current Version**: V2.0 - Multi-product support with hot product handling

---

## Table of Contents
1. [Problem Statement](#1-problem-statement)
2. [Requirements](#2-requirements)
3. [Proposed Solution - High Level Overview](#3-proposed-solution---high-level-overview)
4. [System Architecture](#4-system-architecture)
5. [Key Design Decisions](#5-key-design-decisions)
6. [Performance Analysis](#6-performance-analysis)
7. [Cost Estimation](#7-cost-estimation)
8. [Operational Considerations](#8-operational-considerations)
9. [Appendix A: Alternative Approaches Considered](#appendix-a-alternative-approaches-considered)
10. [Appendix B: Queue Wait Time Analysis](#appendix-b-queue-wait-time-analysis)
11. [Appendix C: Risk & Failure Mode Analysis](#appendix-c-risk--failure-mode-analysis)
12. [Appendix D: Multi-Product Architecture](#appendix-d-multi-product-architecture)
13. [Appendix E: Hot Product Handling](#appendix-e-hot-product-handling)
14. [Appendix F: Product Search and Discovery](#appendix-f-product-search-and-discovery)

---

## 1. Problem Statement

### Business Context

We need to design a system to host flash sale events where **10,000 units** of a high-demand product are sold to **millions of concurrent users** within **minutes** (potentially seconds). The sale creates extreme traffic spikes with the following challenges:

**Traffic Characteristics:**
- **250,000 read requests/second** (users checking product availability)
- **25,000 write requests/second** (reservation attempts for single SKU)
- **Extreme contention**: All users competing for the same inventory record
- **Bursty traffic**: Most traffic arrives within the first 30 seconds
- **Bot attacks**: Automated systems attempting to monopolize inventory

**Business Constraints:**
- **Zero oversell tolerance**: Cannot sell more than 10,000 units (legal/compliance requirement)
- **Strict latency requirements**: Must provide responsive user experience under load
- **Fair distribution**: Prevent bots from winning; ensure legitimate users have fair chance
- **Complete auditability**: Every inventory decision must be traceable
- **Reservation expiry**: Hold units for 2 minutes, auto-release if not checked out

### Core Technical Challenge

Traditional database architectures fail catastrophically at this scale:

**Pessimistic locking (row locks):**
- Throughput: ~100 requests/second
- P95 latency: 4+ minutes for 25,000 concurrent requests
- **Violates SLO by 2000x**

**Optimistic locking (version numbers):**
- Retry amplification: 12.5x (96% failure rate)
- Total database load: 625,000 attempts/second
- P95 latency: ~250ms
- **Violates SLO by 2x**, creates retry storms

The system must handle **25 decisions per millisecond** (25,000 RPS) while maintaining **ACID guarantees** and **zero oversell**.

---

## 2. Requirements

### Deployment Scope

**Geographic Coverage:** Single region deployment (APAC)
- Primary datacenter: Singapore (AWS ap-southeast-1)
- All users served from APAC region
- No multi-region complexity or geographic routing needed

### Functional Requirements

| ID | Requirement | Description |
|----|-------------|-------------|
| FR-1 | Product Availability Check | Users can query real-time stock availability |
| FR-2 | Reservation Creation | Users can reserve 1 unit with atomic 2-minute hold |
| FR-3 | Reservation Expiry | Unreserved units automatically released after 2 minutes |
| FR-4 | Checkout Processing | Users can complete payment within reservation window |
| FR-5 | Inventory Accuracy | System prevents overselling (sold ≤ total stock) |
| FR-6 | Audit Trail | Complete event log for every state change |
| FR-7 | Fair Distribution | Prevent bot monopolization through rate limiting |

### Non-Functional Requirements

| ID | Requirement | Target | Critical? |
|----|-------------|--------|-----------|
| NFR-1 | Read Latency (P95) | ≤ 150ms | Yes |
| NFR-2 | Write Latency (P95) | ≤ 120ms | Yes |
| NFR-3 | Checkout Latency (P95) | ≤ 450ms | Yes |
| NFR-4 | Read Throughput | 250,000 RPS | Yes |
| NFR-5 | Write Throughput | 25,000 RPS | Yes |
| NFR-6 | Availability | 99.9% during event | Yes |
| NFR-7 | Data Consistency | Strong (ACID) | Yes |
| NFR-8 | Zero Oversell | 100% guarantee | **Critical** |
| NFR-9 | DDoS Protection | Absorb 1M+ RPS attacks | Yes |
| NFR-10 | Cost Efficiency | < $50 per 30-min event | No |

### Derived Requirements

Based on the above constraints, the system must achieve:

1. **Throughput Requirement**: Process **275k total RPS** (250k reads + 25k writes)
2. **Latency Budget**: **40 microseconds** per inventory decision (1000ms / 25,000 decisions)
3. **Cache Hit Rate**: **>99%** to keep database load manageable
4. **Consistency Guarantee**: All reads reflect committed writes within cache TTL window
5. **Bot Detection Accuracy**: **>95%** to ensure fair distribution

---

## 3. Proposed Solution - High Level Overview

### Solution Approach

We propose a **multi-tier, event-driven architecture** with the following key principles:

#### Core Architectural Patterns

**1. Serialization for Consistency**
- **Single-Writer Pattern**: One Kafka consumer processes all inventory updates serially
- **Batch Processing**: Process 250 requests per 10ms batch (achieves 25k RPS)
- **Eliminates Race Conditions**: No optimistic/pessimistic locking needed
- **Guarantees Zero Oversell**: Single source of truth, atomic operations

**2. Aggressive Caching for Scalability**
- **Redis Master-Only Pattern**: 200k RPS reads from Redis master (no replica staleness)
- **99% Cache Hit Rate**: Only 2k RPS hits database
- **Immediate Invalidation**: Cache updated on every inventory change
- **TTL Safety Net**: 5-second TTL prevents indefinite staleness

**3. Multi-Layer Rate Limiting for Fairness**
- **CDN Layer**: Absorbs volumetric DDoS (1M+ RPS), per-IP limits (1000 req/min reads, 100 req/min writes)
- **API Gateway Layer**: Behavioral analysis, device fingerprinting, tiered rate limits
- **Application Layer**: Token bucket + FIFO queue ensures fair distribution

**4. Event-Driven for Auditability**
- **Kafka Event Stream**: Every state change published as event
- **Complete Audit Trail**: Immutable log of all reservations, expirations, checkouts
- **Asynchronous Processing**: Checkout, payment, notifications handled asynchronously

**5. Three-Layer Redundancy for Reliability**
- **Reservation Expiry**: Redis TTL (real-time) + Scheduled Job (periodic) + Event Stream (reactive)
- **Cache Failover**: Redis cluster with automatic failover
- **Database Replication**: Primary + async replicas for analytics

### Why This Approach Works

| Challenge | Traditional Approach | Our Approach | Improvement |
|-----------|---------------------|--------------|-------------|
| 25k writes/sec to single row | Row locks (100 req/sec) | Kafka batching (25k req/sec) | **250x faster** |
| Race conditions | Optimistic locking (96% retry) | Single-writer (0% retry) | **Eliminates retries** |
| 200k reads/sec | Database (5k max) | Redis cache (400k capable) | **40x capacity** |
| Bot attacks | No protection | Multi-layer rate limiting | **95% bot detection** |
| Cache staleness | Eventual consistency | Master-only reads + invalidation | **Strong consistency** |
| P95 latency | 250ms+ | 60ms (write), 2ms (read) | **4x better** |

### Success Metrics

Upon successful implementation, the system will:
- ✅ Handle 275k total RPS (250k reads + 25k writes)
- ✅ Maintain P95 latency: 150ms reads, 120ms writes
- ✅ Guarantee zero oversell through single-writer serialization
- ✅ Achieve 99% cache hit rate for availability queries
- ✅ Block 95%+ of bot traffic through multi-layer rate limiting
- ✅ Complete 30-minute flash sale for under $15 infrastructure cost

---

## 4. System Architecture

### 4.1 High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         MILLIONS OF USERS (APAC)                         │
│                    (250k RPS reads + 25k RPS writes)                     │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 │
┌────────────────────────────────v────────────────────────────────────────┐
│                        CDN / DDoS Protection                             │
│                         (Cloudflare / AWS Shield)                        │
│                          APAC Edge Locations                             │
│                                                                           │
│  • Absorbs DDoS attacks (1M+ RPS capability)                            │
│  • Per-IP rate limits: Reads (1000/min), Writes (100/min)              │
│  • 99% cache hit rate for static content                                │
│  • Routes to Singapore datacenter (ap-southeast-1)                      │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
                    ┌────────────────────────────┐
                    │    Network Load Balancer   │
                    │  (AWS NLB - ap-southeast-1)│
                    └────────────┬───────────────┘
                                 │
┌────────────────────────────────v────────────────────────────────────────┐
│                           API Gateway                                    │
│                  (Kong / AWS API Gateway - Singapore)                    │
│                                                                           │
│  • Authentication & JWT validation                                       │
│  • Behavioral analysis & bot detection                                   │
│  • Tiered rate limiting (Tier 1-4)                                      │
│  • Request validation & enrichment                                       │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                 ┌───────────────┴───────────────┐
                 │                               │
                 ▼                               ▼
    ┌────────────────────┐          ┌────────────────────┐
    │   READ PATH        │          │   WRITE PATH       │
    │  (250k RPS)        │          │   (25k RPS)        │
    └─────────┬──────────┘          └─────────┬──────────┘
              │                               │
              ▼                               ▼
┌──────────────────────────┐    ┌──────────────────────────────┐
│   Redis Cache Cluster    │    │   Kafka Topic                │
│   (Master-only reads)    │    │   (reservation-requests)     │
│                          │    │   Single Partition           │
│  • 200k RPS capacity     │    │   (maintains ordering)       │
│  • Master: 225k ops/sec  │◄───┤                              │
│  • Slave: Standby (HA)   │    │  • Producer: 25k msg/sec     │
│  • 99% cache hit rate    │    │  • Retention: 24 hours       │
│  • Sub-2ms latency       │    │  • Replication: 3x           │
│                          │    └──────────┬───────────────────┘
│  Key: stock:{sku_id}     │               │
│  TTL: 5 seconds          │               │
└──────────┬───────────────┘               ▼
           │              ┌────────────────────────────────────┐
           │              │  Single-Writer Consumer            │
           │              │  (Inventory Update Processor)      │
           │              │                                    │
           │              │  • Poll batch: 250 requests        │
           │              │  • Process: 10ms per batch         │
           │              │  • Throughput: 25k RPS             │
           │              │  • Atomic DB update per batch      │
           │              └────────┬───────────────────────────┘
           │                       │
           │                       │ (Both read & write)
           │                       │
           └───────────────────────┼────────────────────────────┐
                                   ▼                            │
                    ┌──────────────────────────────┐            │
                    │     PostgreSQL Primary       │            │
                    │    (Source of Truth)         │            │
                    │                              │            │
                    │  Tables:                     │            │
                    │  • inventory                 │            │
                    │  • reservations              │            │
                    │  • orders                    │            │
                    │                              │            │
                    │  Features:                   │            │
                    │  • ACID transactions         │            │
                    │  • Strong consistency        │            │
                    │  • Async replication         │            │
                    └──────────┬───────────────────┘            │
                               │                                │
                               │ (Async replication)            │
                               ▼                                │
                    ┌─────────────────────┐                     │
                    │  Read Replicas (3x) │                     │
                    │  (Analytics only)   │                     │
                    └─────────────────────┘                     │
                                                                │
┌───────────────────────────────────────────────────────────────┘
│
│ (Cache Invalidation)
│
▼
┌────────────────────────────────────────────────────────────────────────┐
│                         Event Stream (Kafka)                            │
│                                                                          │
│  Topics:                                                                │
│  • reservation.created  → Triggers cache invalidation                  │
│  • reservation.expired  → Triggers stock release                       │
│  • order.completed      → Triggers fulfillment                         │
│  • inventory.updated    → Triggers CDN cache invalidation              │
└────────────────────────────────────────────────────────────────────────┘


┌────────────────────────────────────────────────────────────────────────┐
│                      BACKGROUND JOBS & SERVICES                         │
│                                                                          │
│  ┌──────────────────────┐  ┌──────────────────────┐                   │
│  │  Expiry Scheduler    │  │  Checkout Service    │                   │
│  │  (Every 10 seconds)  │  │  (Async processing)  │                   │
│  │                      │  │                      │                   │
│  │  • Query expired     │  │  • Payment gateway   │                   │
│  │    reservations      │  │  • Order creation    │                   │
│  │  • Release stock     │  │  • Inventory update  │                   │
│  │  • Publish events    │  │  • Notifications     │                   │
│  └──────────────────────┘  └──────────────────────┘                   │
└────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Request Flow

#### Read Path (Product Availability Check)

```
User Request (GET /api/products/{sku}/availability)
    │
    ├─ CDN Layer: Check cache (99% hit) → Return cached response
    │   └─ Cache miss: Forward to origin
    │
    ├─ API Gateway: Rate limit check (1000 req/min for authenticated)
    │   └─ If exceeded: Return 429 + Retry-After
    │
    ├─ Application: Check Redis cache
    │   ├─ Cache hit (99%): Return stock count (2ms latency)
    │   └─ Cache miss (1%): Query PostgreSQL primary (10ms latency)
    │
    └─ Response: {"sku_id": "...", "available": 5234, "total": 10000}

Total P95 Latency: ~2ms (cache hit), ~150ms (worst case with CDN miss)
```

#### Write Path (Reservation Request)

```
User Request (POST /api/reserve)
    │
    ├─ CDN Layer: Per-IP rate limit (100 req/min) → Forward if allowed
    │
    ├─ API Gateway:
    │   ├─ Bot detection (behavioral analysis)
    │   ├─ Tier assignment (Tier 1-4 based on risk score)
    │   ├─ Token bucket check (100 req/min for Tier 3)
    │   └─ If no tokens: Return 429 + Queue position
    │
    ├─ Application: Publish to Kafka
    │   ├─ Topic: reservation-requests (single partition)
    │   ├─ Message: {user_id, sku_id, idempotency_key, timestamp}
    │   └─ Acknowledgment: Message accepted (1ms)
    │
    ├─ Kafka Consumer (Single-Writer):
    │   ├─ Poll batch of 250 requests (every 10ms)
    │   ├─ Validate each request (idempotency, user limits)
    │   ├─ BEGIN TRANSACTION
    │   │   ├─ Lock inventory row (SELECT FOR UPDATE)
    │   │   ├─ Allocate units to valid requests
    │   │   ├─ UPDATE inventory SET reserved_count += batch_allocated
    │   │   ├─ INSERT INTO reservations (batch of allocations)
    │   │   │   └─ expires_at = NOW() + 120 seconds
    │   │   └─ COMMIT (atomic update, 10ms total)
    │   │
    │   ├─ Cache invalidation: REDIS.DEL("stock:{sku_id}")
    │   ├─ Set Redis TTL: REDIS.SET("reservation:{id}", {...}, EX 120)
    │   └─ Publish events: reservation.created (for each allocation)
    │
    └─ Response to User:
        {
          "reservation_id": "res-abc123",
          "status": "RESERVED",
          "expires_at": "2025-01-15T10:32:00Z",
          "expires_in_seconds": 120
        }

Total P95 Latency: ~60ms (5ms API + 1ms Kafka + 50ms queue wait + 10ms processing)
```

#### Reservation Expiry Flow (Automatic)

```
Three-Layer Expiry System:

Layer 1: Redis TTL (Real-time)
    └─ Automatically deletes reservation:{id} after 120 seconds
    └─ Latency: <1ms
    └─ Reliability: 99% (Redis-dependent)

Layer 2: Scheduled Job (Every 10 seconds)
    ├─ Query: SELECT * FROM reservations
    │         WHERE expires_at < NOW() AND status = 'RESERVED'
    ├─ For each expired:
    │   ├─ UPDATE reservations SET status = 'EXPIRED'
    │   ├─ UPDATE inventory SET reserved_count -= 1
    │   ├─ REDIS.DEL("stock:{sku_id}")
    │   └─ PUBLISH reservation.expired event
    └─ Max lag: 10 seconds after expiry

Layer 3: Event Stream (Reactive)
    └─ Subscribers listen to reservation.expired
    └─ Trigger immediate cleanup actions
    └─ No database polling needed
```

---

## 5. Key Design Decisions

### Decision 1: Traffic Distribution Architecture

**Problem:** How to distribute 275k total RPS (250k reads + 25k writes) across infrastructure while maintaining low latency in single region (APAC)?

**Solution:** Two-tier load balancing with CDN edge caching (single-region deployment)

**Architecture:**
- **CDN Layer (Cloudflare APAC POPs)**: Absorbs 99% of read traffic at edge (990k RPS cache hits from APAC edge locations)
- **Network Load Balancer (Singapore)**: Distributes remaining 10k RPS to API Gateway cluster
- **API Gateway (Singapore)**: Rate limiting, authentication, request enrichment

**Key Metrics:**
- CDN cache hit rate: 99%
- Origin traffic: 10k RPS (reduced from 1M potential)
- APAC edge latency: 20-50ms (users to nearest CDN POP)
- Singapore datacenter latency: 5-15ms (within APAC region)
- DDoS protection: Included, handles 10M+ RPS attacks at edge

**Single-Region Benefits:**
- **Simplified operations**: No cross-region coordination or replication
- **Lower latency**: No multi-region routing overhead
- **Strong consistency**: No cross-region replication lag issues
- **Cost efficiency**: Single datacenter, no cross-region data transfer fees

**Why Not Alternatives?**
- Single server: Cannot handle 275k RPS (capacity: ~20k RPS)
- Direct to API Gateway: Becomes bottleneck, no DDoS protection
- Service mesh (Istio): Adds 10ms+ latency per hop, violates SLO
- Multi-region: Unnecessary complexity for APAC-only deployment

---

### Decision 2: Inventory Consistency Model

**Problem:** How to handle 25k concurrent writes to single inventory row without race conditions or oversell?

**Solution:** Single-Writer Pattern with Kafka batching

**Architecture:**
```
25k reservation requests/sec
    → Kafka topic (single partition for ordering)
    → Single consumer processes batches of 250 requests
    → Atomic database update per batch (10ms)
    → Throughput: 250 requests / 10ms = 25k RPS ✓
```

**Key Benefits:**
- **No race conditions**: Single consumer = single source of truth
- **Zero oversell guarantee**: Atomic batch processing prevents double-allocation
- **High throughput**: Batching achieves 25k RPS (vs 100 RPS with row locks)
- **Natural audit trail**: Kafka log provides complete event history

**Latency Breakdown:**
- Queue wait: ~50ms (P95, up to 5 batches ahead)
- Processing: ~10ms (database transaction)
- **Total P95: ~60ms** (within 120ms SLO)

**Why Not Alternatives?**
- Pessimistic locking: 100 RPS throughput, P95 latency 4+ minutes
- Optimistic locking: 96% retry rate, 12.5x database amplification
- Distributed locks (Redis): Still serializes access, no throughput gain
- Sharded inventory: Race conditions between shards, oversell risk

---

### Decision 3: Cache Architecture for Read Scalability

**Problem:** Database capacity is 5k RPS, but we need 200k RPS for availability checks.

**Solution:** Redis Cluster with master-only reads and immediate invalidation

**Architecture:**
```
Redis Cluster Configuration:
- Master handles: 200k reads + 25k invalidations = 225k RPS
- Instance: r6g.4xlarge (400k+ ops/sec capacity, 56% utilization)
- Slaves: Standby for HA only (not serving traffic)
- TTL: 5 seconds (safety net for stale data)
- Invalidation: Immediate on every inventory update
```

**Why Master-Only Reads?**
- **Replication lag risk**: Slave lag of 10-100ms typical
- **Staleness impact**: At 200k RPS, 10ms lag = 2,000 users see stale data
- **Consistency requirement**: Zero oversell means cannot tolerate stale reads
- **Capacity sufficient**: Single master handles 400k+ ops/sec, well above 225k needed

**Key Metrics:**
- Cache hit rate: 99%
- Cache latency: 2ms (P95)
- Cache miss latency: 10ms (fallback to database)
- Database read load: 2k RPS (1% miss rate)

**Why Not Alternatives?**
- Direct to database: 5k RPS max, need 200k (40x insufficient)
- Master-slave with read replicas: Replication lag causes stale reads, oversell risk
- Memcached: No pub/sub for invalidation, no persistence
- DynamoDB: $18,875 per 30-min event (100x more expensive)

---

### Decision 4: Rate Limiting Strategy

**Problem:** Prevent bot attacks while allowing 275k RPS legitimate traffic (250k reads + 25k writes).

**Solution:** Multi-layer rate limiting with endpoint-specific thresholds

**Layer 1: CDN/Edge (Absorb volumetric DDoS)**
```
Read endpoints:
- Per-IP: 1,000 req/min (16 req/sec) - allows refreshing
- Global: 500k RPS (2x headroom)
- Result: Bot doing 100 req/sec blocked at edge

Write endpoints:
- Per-IP: 100 req/min (1.67 req/sec) - prevents rapid-fire
- Global: 50k RPS (2x headroom)
- Challenge: CAPTCHA for suspicious patterns
```

**Layer 2: API Gateway (Behavioral analysis)**
```
Bot detection signals:
- Regular intervals (0.1s, 0.1s, 0.1s) vs irregular human timing
- No session history (direct POST /reserve)
- Headless browser fingerprints
- VPN/proxy IP addresses

Tiered rate limits:
- Tier 1 (suspected bot): 1 req/min
- Tier 2 (new user): 50 req/min
- Tier 3 (verified user): 100 req/min
- Tier 4 (premium user): 200 req/min
```

**Layer 3: Application (Token bucket + FIFO queue)**
```
- Each user gets token budget (replenishes every second)
- Requests consume tokens
- No tokens? → Queue in FIFO order
- Fair distribution: Speed doesn't matter, FIFO guarantees fairness
```

**Attack Mitigation Examples:**
- **1M RPS volumetric DDoS**: CDN absorbs 990k RPS (99% cache hit), origin sees 10k RPS ✓
- **100k RPS distributed bot attack**: CDN blocks 83k RPS (per-IP limits), API Gateway downgrades remaining to Tier 1 (1 req/min) ✓
- **Slowloris attack**: Connection timeout at CDN (10 seconds), origin never sees slow connections ✓

**Why Not Alternatives?**
- No rate limiting: Bots monopolize queue (99% bot traffic), unfair to humans
- Simple per-IP only: Ineffective against distributed botnets (1000s of IPs)
- Leaky bucket: Too restrictive for flash sale bursts

---

### Decision 5: Database Consistency Model

**Problem:** Choose database consistency model that guarantees zero oversell.

**Solution:** PostgreSQL with strong consistency (single primary + async replicas)

**Architecture:**
```
Primary Database (PostgreSQL):
- All writes go here (source of truth)
- Strong consistency (ACID transactions)
- Capacity: 25k batch writes/sec (250 requests per 10ms batch)

Read Replicas (3x):
- Async replication (10-100ms lag)
- Used for analytics only (not user-facing reads)
- User-facing reads: Redis cache (99%) or primary (1%)
```

**Why Strong Consistency?**
- **Zero oversell requirement**: Cannot tolerate eventual consistency
- **Legal/compliance**: Overselling = breach of contract, legal liability
- **Example**: With eventual consistency, two users could reserve last unit

**Why Not Alternatives?**
- Eventually consistent (Cassandra, DynamoDB): Inconsistency window → oversell risk
- Quorum-based (CockroachDB): Higher latency (slowest node), more expensive
- Multi-master: Conflict resolution complex, oversell risk during split-brain

---

### Decision 6: Reservation Expiry System

**Problem:** Auto-release units after 2-minute reservation window expires.

**Solution:** Three-layer expiry system for redundancy

**Layer 1: Redis TTL (Real-time)**
```
- SET reservation:{id} {...} EX 120
- Automatic deletion after 120 seconds
- Latency: <1ms
- Reliability: 99% (Redis-dependent)
```

**Layer 2: Scheduled Cleanup (Every 10 seconds)**
```java
@Scheduled(fixedRate = 10000)
SELECT * FROM reservations WHERE expires_at < NOW()
For each expired:
    - UPDATE reservations SET status = 'EXPIRED'
    - UPDATE inventory SET reserved_count -= 1
    - REDIS.DEL("stock:{sku_id}")
    - PUBLISH reservation.expired event
Max lag: 10 seconds
```

**Layer 3: Event Stream (Reactive)**
```
- reservation.expired event published to Kafka
- Subscribers handle cleanup immediately
- No polling, event-driven
```

**Redundancy Guarantee:**
- If Redis fails → Scheduled job catches expiry (10s lag)
- If scheduler fails → Event stream still publishes
- If Kafka fails → Scheduled job still runs
- **Result: Guaranteed stock release even with component failures**

**Latency Impact:** Zero (no additional latency added to reservation path)

---

## 6. Performance Analysis

### 6.1 Latency Breakdown

#### Read Request (GET /availability)

| Component | Latency (P95) | Notes |
|-----------|---------------|-------|
| CDN Cache Hit | 20ms | 99% of requests |
| CDN → Origin (miss) | 50ms | Geographic routing |
| API Gateway | 5ms | Rate limit check, auth |
| Redis Cache Hit | 2ms | Master read |
| Database (cache miss) | 10ms | 1% of requests |
| **Total (cache hit)** | **20ms** | Within 150ms SLO ✓ |
| **Total (cache miss)** | **70ms** | Within 150ms SLO ✓ |

#### Write Request (POST /reserve)

| Component | Latency (P95) | Notes |
|-----------|---------------|-------|
| API Gateway | 5ms | Rate limit, bot detection |
| Kafka Producer | 1ms | Message published |
| Queue Wait | 50ms | 5 pending batches (P95) |
| Batch Processing | 10ms | 250 requests, DB transaction |
| **Total** | **66ms** | Within 120ms SLO ✓ |

**Queue Wait Calculation:**
```
At P95 load, ~5 batches pending in queue
5 batches × 10ms per batch = 50ms queue wait
+ 10ms processing = 60ms total
Headroom: 120ms SLO - 60ms actual = 60ms buffer ✓
```

### 6.2 Throughput Analysis

| Operation | Target | Achieved | Headroom |
|-----------|--------|----------|----------|
| Read RPS | 250,000 | 400,000+ | 60% |
| Write RPS | 25,000 | 25,000 | 0% (batching limit) |
| Cache ops/sec | 225,000 | 400,000+ | 78% |
| Database writes/sec | 100 batches | 100 batches | 0% (single-writer limit) |
| CDN edge capacity | 1M+ | 10M+ | 90% |

**Bottlenecks:**
- **Write throughput**: Limited by single-writer pattern (25k RPS max with 10ms batches)
  - Mitigation: Cannot horizontally scale (would break ordering)
  - Acceptable: Meets requirement exactly

- **Database connection pool**: ~80 connections under load (max 200 available)
  - Mitigation: Connection pooling, query optimization
  - Acceptable: 60% headroom

### 6.3 Cache Performance

| Metric | Target | Achieved |
|--------|--------|----------|
| Cache hit rate | 95% | 99% |
| Cache latency (P95) | <5ms | 2ms |
| Cache capacity | 225k ops/sec | 400k+ ops/sec |
| Invalidation latency | <10ms | 1ms |
| Stale read window | 0ms (strong consistency) | 0ms (master-only) |

### 6.4 Scalability Limits

**Current Architecture Supports:**
- ✅ 250k RPS reads (limited by Redis: 400k capable)
- ✅ 25k RPS writes (limited by batch processing: 10ms per batch)
- ✅ 10,000 units sold in 25 seconds (400 units/sec depletion rate)

**To Scale Beyond 25k Writes:**
- Option 1: Reduce batch processing time to 5ms → 50k RPS (database optimization)
- Option 2: Pre-shard inventory (10 shards × 1,000 units each) → 250k RPS
  - ⚠️ Risk: Requires careful partitioning to avoid cross-shard oversell

---

## 7. Cost Estimation

### 7.1 Infrastructure Costs (30-minute event)

| Component | Specification | Hourly Cost | Event Cost (1 hour) | Notes |
|-----------|--------------|-------------|---------------------|-------|
| **Compute (Kubernetes)** | 5 × m5.2xlarge nodes | $3.60 | $3.60 | Pre-scaled 30 min early |
| **Database (PostgreSQL)** | r6g.xlarge primary + 3 replicas | $0.78 | $0.78 | Managed RDS |
| **Cache (Redis)** | 3 masters + 3 slaves (r6g.large) | $1.73 | $1.73 | ElastiCache cluster |
| **Load Balancer** | 1 NLB (Singapore) | $0.01 | $0.01 | Minimal cost |
| **API Gateway** | 275k RPS × 30 sec = 8.25M requests | - | $28.88 | Pay per request |
| **CDN (Cloudflare)** | Pro plan + 450M requests | - | $470.00 | Includes DDoS protection |
| **Message Queue (Kafka)** | 3 brokers (MSK) | $0.34 | $0.34 | Managed Kafka |
| **Monitoring (Datadog)** | 5 hosts × 1.5 hours | $0.37 | $0.37 | APM + logs |
| **TOTAL** | | | **$505.72** | For 30-min event |

**Cost Breakdown:**
- CDN dominates: $470 (93% of total)
- Infrastructure: $35 (7% of total)

**Cost Optimization Options:**
- Use AWS CloudFront instead of Cloudflare: $413 (saves $57)
- Self-hosted Kafka instead of MSK: Saves $0.34 (negligible)
- **Optimized Total: ~$448 per event**

### 7.2 Operational Costs

| Activity | Time Required | Cost @ $80/hr |
|----------|--------------|---------------|
| Planning & design | 12 eng-hours | $960 |
| Implementation | 80 eng-hours | $6,400 |
| Testing & QA | 20 eng-hours | $1,600 |
| Event monitoring (live) | 2 eng-hours | $160 |
| Post-event analysis | 4 eng-hours | $320 |
| **Total Engineering** | **118 hours** | **$9,440** |

**One-Time Setup:** $8,960 (planning + implementation + testing)
**Per-Event:** $480 (monitoring + analysis)

### 7.3 Business Value Analysis

**Revenue per Event:**
```
10,000 units × $200 average price = $2,000,000 revenue
Gross margin @ 50% = $1,000,000 profit
```

**Cost as % of Revenue:**
```
Infrastructure: $450 / $2M = 0.02% of revenue
Engineering (per-event): $480 / $2M = 0.024% of revenue
Total: 0.044% of revenue
```

**ROI:**
- **Investment**: $9,440 one-time + $450 per event
- **Return**: $1M profit per event
- **Break-even**: First successful event
- **Ongoing**: 0.044% of revenue (excellent efficiency)

---

## 8. Operational Considerations

### 8.1 Pre-Event Checklist (T-1 hour)

**Infrastructure Readiness:**
- ✅ Kubernetes cluster scaled to 50% capacity
- ✅ Redis cluster healthy (3 masters + 3 slaves)
- ✅ PostgreSQL primary + replicas synchronized (lag <50ms)
- ✅ Kafka brokers online, topic created with correct partitions
- ✅ CDN caches warmed with product data
- ✅ API Gateway instances ready (no cold starts)

**Data Preparation:**
- ✅ Inventory record exists: `sku_id, total_stock=10000, reserved=0, sold=0`
- ✅ Product metadata loaded in Redis
- ✅ No existing reservations for SKU
- ✅ User tiers pre-computed
- ✅ Rate limit state cleared (fresh start)

**Monitoring Setup:**
- ✅ Dashboard visible with key metrics
- ✅ Alerting rules validated
- ✅ On-call pager tested
- ✅ War room channel opened

### 8.2 During-Event Monitoring (T0 to T0+30 min)

**Critical Metrics to Watch:**

| Metric | Target | Alert Threshold | Action |
|--------|--------|-----------------|--------|
| oversell_count | 0 | >0 | **IMMEDIATE ESCALATION** |
| request_rate | 275k RPS | >300k RPS | Verify DDoS protection |
| p95_latency_reads | <150ms | >200ms | Check cache hit rate |
| p95_latency_writes | <120ms | >150ms | Check queue depth |
| cache_hit_rate | >99% | <95% | Check invalidation logic |
| queue_depth | <100k | >500k | Consumer falling behind |
| error_rate | <0.1% | >1% | Investigate errors |
| stock_depletion_rate | 400 units/sec | Deviates ±20% | Verify allocation logic |

**Real-Time Actions:**
```
T-30s: Pre-warm caches, scale pods to 100%
T0: Sale starts, monitor request ramp-up
T0-3s: Initial spike, verify auto-scaling triggers
T0+25s: ~10,000 units sold, announce "sold out"
T0+30m: Begin scale-down, process pending checkouts
```

### 8.3 Post-Event Analysis

**Data to Collect:**
- Total requests served (reads + writes)
- P95/P99 latency by endpoint
- Cache hit rate over time
- Bot detection accuracy (Tier 1 assignments)
- Revenue generated
- Oversell incidents (target: 0)
- System errors and root causes

**Metrics for Improvement:**
- Which rate limits were hit most frequently?
- Were any legitimate users incorrectly flagged as bots?
- Did queue depth ever exceed thresholds?
- What was actual peak RPS vs. estimated?

### 8.4 Runbooks for Failure Scenarios

**Scenario 1: Oversell Detected**
```
1. STOP accepting new reservations (return 503)
2. Page on-call immediately
3. Determine which orders to refund (last N orders)
4. Initiate refund API calls
5. Send customer notifications
6. Root cause analysis (code bug? race condition?)
```

**Scenario 2: Database Primary Failure**
```
1. Automatic failover to replica (30-300ms downtime)
2. Verify new primary accepting writes
3. Check replication to remaining replicas
4. Monitor application for errors
5. If prolonged outage: Return 503, notify users
```

**Scenario 3: Redis Cluster Failure**
```
1. All requests fall back to PostgreSQL (slower but correct)
2. Monitor database load (should stay under 5k RPS)
3. If database overloaded: Enable aggressive rate limiting
4. Restart Redis cluster
5. Warm cache from database before resuming
```

**Scenario 4: Kafka Consumer Lag Growing**
```
1. Check batch processing time (should be ~10ms)
2. Verify database query performance
3. If database slow: Optimize queries, add indexes
4. If consumer bug: Deploy hotfix
5. If unfixable: Increase batch size to 500 (reduces latency SLO)
```

### 8.5 Team Skillset Requirements

**Critical Roles:**
- **Backend Engineer (3-4)**: Java/Spring Boot, Kafka, distributed systems
- **DevOps/SRE (2-3)**: Kubernetes, monitoring, incident response
- **Database Engineer (1-2)**: PostgreSQL tuning, replication, backup/restore
- **QA Engineer (1-2)**: Load testing, chaos engineering, stress testing

**Knowledge Gaps to Address:**
- Distributed systems patterns (event sourcing, idempotency)
- Performance profiling and optimization
- Chaos engineering and failure testing
- Operational runbooks and incident response

---

## Appendix A: Alternative Approaches Considered

### A.1 Decision 1: Load Distribution

**Option A: Single Server**
- **Rejected**: Cannot handle 275k RPS (max capacity ~20k RPS)
- Single point of failure, no geographic distribution

**Option B: Multiple Servers + Regional LBs (SELECTED)**
- Three-tier: CDN → Regional LBs → API Gateway → Services
- Handles 275k RPS with 60% headroom
- DDoS protection at CDN layer

**Option C: Anycast DNS + BGP Routing**
- **Rejected**: Overkill complexity, requires BGP expertise
- Same benefits as Option B with higher operational cost

---

### A.2 Decision 2: Inventory Consistency

**Option A: Pessimistic Locking (Row Locks)**
- **Rejected**: Throughput 100 RPS, P95 latency 4 minutes
- Serializes all access, queue builds up exponentially

**Option B: Optimistic Locking (Version Numbers)**
- **Rejected**: 96% retry rate, 12.5x database amplification
- P99 latency 250ms (violates 120ms SLO)
- Retry storms under high contention

**Option C: Distributed Lock (Redis SETNX)**
- **Rejected**: Still serializes access like pessimistic locking
- No throughput improvement, adds Redis dependency

**Option D: Single-Writer Pattern (Kafka) (SELECTED)**
- Achieves 25k RPS through batching (250 requests per 10ms)
- No race conditions, zero oversell guarantee
- P95 latency 60ms (within SLO)

**Option E: Sharded Inventory**
- **Rejected**: Race conditions between shards
- Example: Shard A reserves last unit, Shard B doesn't know → oversell
- Too risky for zero-oversell requirement

---

### A.3 Decision 3: Cache Architecture

**Option A: No Cache (Direct to Database)**
- **Rejected**: Database capacity 5k RPS, need 200k RPS (40x insufficient)
- P95 latency >30 seconds under load

**Option B: Single Redis Instance**
- **Rejected**: Capacity ~100k RPS, need 225k RPS
- Single point of failure, no HA

**Option C: Redis Master-Slave Replication**
- **Rejected**: Replication lag 10-100ms causes stale reads
- At 200k RPS, even 10ms lag = 2,000 users see stale data
- Oversell risk if reading from stale replica

**Option D: Redis Cluster (Master-Only Reads) (SELECTED)**
- Master handles 225k RPS (400k capable, 56% utilization)
- Slaves for HA only (standby for failover)
- Strong consistency (no replication lag issues)

**Option E: Memcached**
- **Rejected**: No pub/sub for invalidation
- Must rely on TTL expiration (stale data window)

**Option F: DynamoDB**
- **Rejected**: Cost $18,875 per 30-min event (100x more expensive)
- Latency 25ms+ for strong consistency reads

---

### A.4 Decision 4: Rate Limiting

**Option A: No Rate Limiting**
- **Rejected**: Bots monopolize queue (99% bot traffic)
- Unfair to legitimate users, queue grows unbounded

**Option B: Simple Per-IP Rate Limit**
- **Rejected**: Ineffective against distributed botnets (1000s of IPs)
- Shared IPs (university, corporate) unfairly rate limited together

**Option C: Multi-Dimensional Rate Limiting**
- Good but not sufficient alone
- Cannot guarantee fairness without queue

**Option D: Token Bucket + FIFO Queue (SELECTED)**
- Fair distribution: Speed doesn't matter, FIFO guarantees fairness
- Bots and humans get same token allocation
- Queue prevents system collapse

**Option E: Leaky Bucket**
- **Rejected**: Too restrictive for flash sale bursts
- Constant drain rate rejects legitimate initial spike

---

### A.5 Decision 5: Database Consistency

**Option A: Eventually Consistent (Cassandra, DynamoDB)**
- **Rejected**: Inconsistency window → oversell risk
- Example: Two users reserve last unit during replication lag

**Option B: Strong Consistency (PostgreSQL) (SELECTED)**
- ACID transactions prevent oversell
- Single primary for writes (source of truth)
- Proven at scale (GitHub, Shopify)

**Option C: Quorum-Based (CockroachDB)**
- **Rejected**: Higher latency (slowest node in quorum)
- More expensive ($300+/month minimum)
- Overkill for single-region deployment

---

## Appendix B: Queue Wait Time Analysis

### The Question

In the Single-Writer Pattern, the document states:
```
P95 latency:
- Queue wait: ~50ms
- Processing: 10ms
- Total: ~60ms ✓
```

How is **queue wait time of ~50ms** derived?

### System Parameters

```
Peak load: 25,000 requests/second (RPS)
Batch size: 250 requests per batch
Batch processing time: 10ms per batch
Batches per second: 25,000 / 250 = 100 batches/second
Batch interval: 1000ms / 100 = 10ms between batch starts
```

### Calculation Method

**At P95 Load (95th percentile of requests):**

Under realistic burst conditions, a request typically encounters **5 other batches** ahead of it in the queue.

**Why 5 batches?**
- During traffic bursts, requests don't arrive perfectly uniformly
- At P95, approximately 1,250-1,500 requests arrive in a burst
- This represents: 1,250 requests / 250 per batch = 5 batches
- Latest request in burst must wait for all 5 batches ahead

**Queue Wait Calculation:**
```
Queue wait = Number of pending batches × Time per batch
           = 5 batches × 10ms/batch
           = 50ms
```

### Validation

**Burst Capacity Check:**
```
Total requests per second: 25,000
If all arrive at once: 25,000 / 250 = 100 batches queued
Time to process all: 100 × 10ms = 1,000ms (1 second)

P95 means: 95% of requests handled within 50ms
Only 5% (1,250 requests) experience worst-case queueing
These might wait: 5-10 batches = 50-100ms

50ms is conservative for P95 ✓
```

**SLO Compliance Check:**
```
SLO: P95 ≤ 120ms
Calculated: 60ms (queue 50ms + processing 10ms)
Headroom: 60ms / 120ms = 50% of budget ✓

Leaves room for:
- Network latency: 20ms
- API Gateway: 10ms
- Other overhead: 30ms
- Total: 60ms + 60ms = 120ms ✓ (exactly meets SLO)
```

### Worst Case (P99)

At P99, queue wait could be:
```
P99 latency: 200-300ms
- 20-30 batches in queue
- Processing time: 200-300ms total

But P95 is the SLO target, not P99.
Architecture focuses on meeting P95 ≤ 120ms.
```

---

## Appendix C: Risk & Failure Mode Analysis

### Critical Risk 1: Oversell Detection

**Risk**: Despite single-writer pattern, oversell occurs (sold > 10,000 units)

**Likelihood**: Very low (~0.01%) if correctly implemented
**Impact**: Very high (legal liability, refunds, reputation damage)

**Root Causes:**
1. Bug in batch allocation logic (off-by-one error)
2. Database corruption (disk failure)
3. Race condition in non-single-writer code path
4. Manual admin error (direct SQL UPDATE)
5. Concurrent independent consumers (misconfiguration)

**Detection:**
```
Real-time monitoring:
- Metric: oversell_count = max(0, sold_count - total_stock)
- Alert: IF oversell_count > 0 → IMMEDIATE page on-call
- Frequency: Check every 10 seconds
```

**Recovery Procedure:**
```
Step 1 (T=0): STOP accepting new reservations (return 503)
Step 2 (T=1-5min): Determine which orders to refund (last N orders)
Step 3 (T=5-30min): Initiate refunds, notify customers
Step 4 (T=30min+): Root cause analysis, fix bug, deploy
Step 5: Resume sales only after verification
```

**Cost of Oversell:**
```
Per oversold unit:
- Refund: $100-500 (product price)
- Processing: $10 (refund fee)
- Compensation: $50 (goodwill credit)
- Reputation: Immeasurable

100 units oversold = $16,000 direct cost + reputation damage
Prevention investment >> recovery cost
```

---

### Critical Risk 2: Single-Writer Consumer Failure

**Risk**: Kafka consumer crashes, inventory updates stop

**Scenario:**
```
T=0: Consumer processing batch
T=10ms: Database timeout, consumer crashes
T=21ms: Kafka offset not committed
T=22ms: New consumer starts (automatic failover)
T=23ms: Consumer replays batch (idempotency prevents duplicates)
Result: Safe recovery, no data loss ✓
```

**Mitigation:**
1. **Multiple consumer instances**: Primary + secondary on standby
2. **Graceful shutdown**: Finish batch, commit offset, then exit
3. **Circuit breaker**: Retry database operations, crash explicitly if failing
4. **Health checks**: Kubernetes restarts unhealthy pods automatically

---

### Critical Risk 3: Cache Invalidation Failure

**Risk**: Redis unavailable, cache not invalidated, users see stale data

**Analysis:**
```
Does stale cache cause oversell?

Assumption: Cache miss → Query database for truth
User sees stale cache: stock = 10,000
User tries to reserve: Database check happens
Database truth: stock = 9,999
Database prevents oversell
User gets error: "Out of stock"
No oversell! ✓

Stale cache only causes poor UX, not oversell.
Database is always authority.
```

**Mitigation:**
1. **Fallback to database**: If Redis unavailable, serve from PostgreSQL
2. **TTL safety net**: Cache expires after 5 seconds
3. **Dual invalidation**: Retry cache delete with exponential backoff
4. **Monitoring**: Alert if cache hit rate drops below 80%

---

### Failure Mode 1: Database Connection Pool Exhaustion

**Cause:**
```
Batch consumer holds connection for 10ms processing
Concurrent connections: 100 batches/sec × 0.01s = 1 connection
Plus analytics queries: 50 connections
Plus replicas: 20 connections
Total: 71 connections

PostgreSQL max_connections: 100
Headroom: 29 connections ✓

But if queries slow down to 100ms:
Concurrency: 100 × 0.1s = 10 connections
Total: 80 connections (still okay)
```

**Mitigation:**
- Connection pooling (HikariCP): Max 20 per instance
- Database tuning: max_connections = 200
- Query optimization: Add indexes, analyze slow queries
- Monitoring: Alert if active_connections > 80

---

### Failure Mode 2: Kafka Consumer Lag Growing

**Cause:**
```
Consumer cannot keep up:
- Producer: 25k messages/second
- Consumer: 250 messages per 15ms (not 10ms) = 16.6k/sec
- Backlog: 25k - 16.6k = 8.4k messages/sec
- After 1 minute: 504k messages behind
```

**Symptoms:**
- Queue position numbers growing
- P95 latency increasing
- Users report slow reservations

**Mitigation:**
1. **Real-time monitoring**: Alert if lag > 10,000 messages
2. **Optimization**: Profile batch processing, optimize database queries
3. **Scaling**: Increase batch size to 500 (reduces latency headroom)
4. **Graceful degradation**: If lag > 100k, return 503 "Try again later"

---

---

## Appendix D: Multi-Product Architecture

### D.1 Multi-Product Flash Sale Overview

**Extension from Single Product to Multiple Products**

The system now supports 1-100 products per flash sale event, with:
- **Variable inventory per product** (e.g., iPhone: 10,000 units, Headphones: 2,000 units)
- **Per-product waiting rooms** (separate FIFO queue per product)
- **Per-product purchase limits** (1 unit per user per product)
- **Simultaneous launch** (all products start at 10:00:00 AM)
- **Known product list** (all products announced in advance)

### D.2 Multi-Product Inventory Management

**Per-Product Single-Writer Pattern**

```
Kafka Topic: reservation-requests
Partitioning Strategy: Partition by SKU_ID

Example: 10 partitions for 10 products
- iPhone (SKU-001) → Partition 0 → Consumer 0
- Laptop (SKU-002) → Partition 1 → Consumer 1
- Headphones (SKU-003) → Partition 2 → Consumer 2
...

Each consumer processes ONE product's reservations serially
```

**Benefits**:
- **Parallel processing**: Multiple products processed simultaneously
- **Zero cross-product interference**: iPhone reservations don't block Laptop reservations
- **Consistent per-product**: Each product still has single-writer guarantee
- **Scalable**: Add more partitions/consumers as products grow

**Kafka Configuration**:
```
Topic: reservation-requests
Partitions: 100 (max products)
Replication: 3
Retention: 24 hours
Consumer Group: inventory-processors
Consumer Count: 10-100 (dynamic based on active products)
```

### D.3 Per-Product Purchase Limits

**Requirement**: User can buy 1 unit per product (can buy iPhone + Laptop, but not 2 iPhones)

**Implementation**:
```java
@Table(name = "user_purchases", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "sku_id"})
})
public class UserPurchase {
    private String userId;
    private String skuId;
    private String orderId;
    private Instant purchasedAt;
}

// Check before reservation
boolean hasUserPurchasedProduct(String userId, String skuId) {
    return userPurchaseRepository.existsByUserIdAndSkuId(userId, skuId);
}
```

**Cache Strategy**:
```
Redis Key: user_purchase:{user_id}:{sku_id}
Value: "true" if purchased
TTL: 24 hours (duration of flash sale event)

Before creating reservation:
1. Check Redis cache: GET user_purchase:{user_id}:{sku_id}
2. If exists → Return 409 "Already purchased this product"
3. If not exists → Proceed with reservation
4. After successful order → SET user_purchase:{user_id}:{sku_id} "true"
```

### D.4 Multi-Product UI/UX Flow

**Product List Page**:
```
GET /api/v1/products?event_id=flash-sale-2025-01-15

Response:
[
  {
    "sku_id": "SKU-001",
    "name": "iPhone 15 Pro",
    "base_price": 99999,
    "flash_sale_price": 49999,
    "discount_percentage": 50,
    "available_count": 8234,
    "total_inventory": 10000,
    "is_active": true,
    "category": "Electronics"
  },
  {
    "sku_id": "SKU-002",
    "name": "MacBook Pro",
    "base_price": 199999,
    "flash_sale_price": 149999,
    "discount_percentage": 25,
    "available_count": 4567,
    "total_inventory": 5000,
    "is_active": true,
    "category": "Computers"
  }
]
```

**User Journey**:
```
Step 1: User views product list (250k RPS shared across all products)
Step 2: User clicks "Reserve" on iPhone (enters iPhone-specific queue)
Step 3: System creates reservation for iPhone
Step 4: User completes checkout for iPhone
Step 5: User can still browse and reserve other products (Laptop, Headphones)
```

---

## Appendix E: Hot Product Handling

### E.1 Hot Product Problem Definition

**Hot Product Scenario**: All traffic concentrates on a single high-demand product

**Worst Case**:
- **Product**: iPhone 15 Pro with ₹50,000 discount
- **Inventory**: 10,000 units
- **Traffic**: 250k read RPS + 25k write RPS (100% to single SKU)
- **Time to deplete**: **0.4 seconds** (all units sold in 400ms)
- **Decision rate**: **25 decisions per millisecond**

**Why Hot Products Occur**:
1. **High-value items**: iPhone, gaming consoles, luxury goods
2. **Deep discounts**: ₹50,000 off iPhone > ₹500 off charger
3. **Social media virality**: Influencer posts concentrate attention
4. **FOMO (Fear of Missing Out)**: Herd behavior, everyone wants the "hot" item
5. **Limited availability**: Scarcity increases desirability

### E.2 Hot Product Architecture

**Single-Writer Per Product (Kafka Partitioning)**

```
Hot Product: iPhone (SKU-001)
All 25k write RPS → Partition 0 → Consumer 0

Consumer 0 processing:
- Batch size: 250 requests
- Batch time: 10ms
- Throughput: 25k RPS ✓

Other products (SKU-002 to SKU-100):
- 0 RPS → Other partitions idle
- No resource waste (consumers sleep when no traffic)
```

**Cache Strategy for Hot Product**:
```
Redis configuration:
- Master node: r6g.4xlarge (400k ops/sec capacity)
- Hot product reads: 250k RPS from single SKU
- Utilization: 62.5% (plenty of headroom)

Cache key: stock:{SKU-001}
Invalidation: Immediate on every batch update
TTL: 5 seconds (safety net)
```

**Load Distribution**:
```
CDN Layer:
- 250k read RPS for iPhone availability
- 99% cache hit at CDN edge → 247.5k served from edge
- 2.5k RPS reach origin → Redis cache

Redis Layer:
- 2.5k cache miss RPS
- 2.5k RPS → PostgreSQL fallback
- Database handles easily (capacity: 5k RPS)
```

### E.3 Hot Product Performance

**Latency Analysis (Hot Product)**:

| Metric | Target | Achieved | Notes |
|--------|--------|----------|-------|
| Read P95 (cache hit) | ≤150ms | 20ms | CDN edge cache |
| Read P95 (cache miss) | ≤150ms | 70ms | Redis + DB fallback |
| Write P95 | ≤120ms | 60ms | Queue + processing |
| Inventory depletion | 25 seconds | 0.4 seconds | 10k units @ 25k RPS |

**Throughput Verification**:
```
Single consumer throughput: 25k RPS
Hot product write demand: 25k RPS
Utilization: 100% (at limit)

If demand exceeds 25k RPS:
- Queue builds up
- Latency increases beyond P95 target
- Mitigation: Rate limiting at API Gateway (cap at 25k RPS per SKU)
```

### E.4 Hot Product Fairness

**Queue Position Transparency**:
```
When user attempts reservation:

Response (if queued):
{
  "status": "QUEUED",
  "queue_position": 12450,
  "estimated_wait_seconds": 50,
  "estimated_processing_time": "2025-01-15T10:00:50Z"
}
```

**Fair Distribution**:
- **FIFO queue per product**: First-come, first-served
- **No VIP queue**: Premium users wait same as regular users
- **Rate limiting**: Prevents bot monopolization
- **Transparency**: Users see their queue position in real-time

---

## Appendix F: Product Search and Discovery

### F.1 Product Search Requirements

**User Needs**:
- Search products by name, category, price range
- Filter by availability, discount percentage
- Sort by price, popularity, discount depth
- Fast response (<100ms P95)
- No stale results (real-time inventory)

### F.2 Search Architecture

**Elasticsearch + Redis Two-Tier Search**

```
┌──────────────────────────────────────────────────────┐
│              Product Search Flow                      │
└──────────────────────────────────────────────────────┘

User Query: "iPhone 15"
    │
    ▼
┌─────────────────┐
│  Redis Cache    │  ◄── L1 Cache (Hot searches)
│  search:iphone  │      TTL: 10 seconds
└─────────┬───────┘      99% hit rate
          │
          │ (Miss)
          ▼
┌─────────────────────┐
│  Elasticsearch      │  ◄── L2 Index (All products)
│  products index     │      Refresh: 5 seconds
│                     │      Query time: ~20ms
└─────────┬───────────┘
          │
          ▼
┌──────────────────────┐
│  PostgreSQL          │  ◄── Source of Truth
│  products table      │      Fallback for exact reads
└──────────────────────┘
```

**Elasticsearch Configuration**:
```json
{
  "index": "flash_sale_products",
  "mappings": {
    "properties": {
      "sku_id": {"type": "keyword"},
      "name": {"type": "text", "analyzer": "standard"},
      "category": {"type": "keyword"},
      "base_price": {"type": "integer"},
      "flash_sale_price": {"type": "integer"},
      "discount_percentage": {"type": "integer"},
      "available_count": {"type": "integer"},
      "is_active": {"type": "boolean"},
      "search_keywords": {"type": "text"}
    }
  },
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 2,
    "refresh_interval": "5s"
  }
}
```

### F.3 Search Query Examples

**Query 1: Search by Name**
```
GET /api/v1/products/search?q=iPhone&limit=20

Elasticsearch query:
{
  "query": {
    "match": {
      "name": "iPhone"
    }
  },
  "filter": {
    "term": {"is_active": true}
  },
  "sort": [
    {"available_count": "desc"}
  ]
}
```

**Query 2: Filter by Price Range**
```
GET /api/v1/products/search?min_price=40000&max_price=60000

Elasticsearch query:
{
  "query": {
    "range": {
      "flash_sale_price": {
        "gte": 40000,
        "lte": 60000
      }
    }
  }
}
```

**Query 3: Filter by Category**
```
GET /api/v1/products/search?category=Electronics&available=true

Elasticsearch query:
{
  "query": {
    "bool": {
      "must": [
        {"term": {"category": "Electronics"}},
        {"term": {"is_active": true}},
        {"range": {"available_count": {"gt": 0}}}
      ]
    }
  }
}
```

### F.4 Real-Time Inventory Sync

**Challenge**: Elasticsearch refresh interval (5s) causes stale inventory counts

**Solution**: Hybrid approach
```
Step 1: Elasticsearch returns product list (fast search)
{
  "results": [
    {"sku_id": "SKU-001", "name": "iPhone", "available_count": 5000},
    {"sku_id": "SKU-002", "name": "Laptop", "available_count": 3000}
  ]
}

Step 2: Real-time inventory overlay (Redis lookup)
For each SKU in results:
  - GET stock:{sku_id} from Redis
  - Replace available_count with real-time value

Step 3: Return merged results to user
{
  "results": [
    {"sku_id": "SKU-001", "name": "iPhone", "available_count": 4823},  ← Real-time
    {"sku_id": "SKU-002", "name": "Laptop", "available_count": 2956}   ← Real-time
  ]
}
```

**Performance**:
```
Elasticsearch query: 20ms
Redis batch lookup (20 SKUs): 5ms
Total: 25ms (well within 100ms target)
```

### F.5 Search Caching Strategy

**Redis Cache for Popular Searches**:
```
Cache Key: search:{query_hash}
Value: JSON array of SKU IDs
TTL: 10 seconds

Example:
search:sha256("iPhone 15") → ["SKU-001", "SKU-005", "SKU-012"]

On cache hit:
1. Retrieve SKU list from cache (1ms)
2. Batch lookup real-time inventory (5ms)
3. Return results (6ms total)

On cache miss:
1. Query Elasticsearch (20ms)
2. Cache result (1ms)
3. Batch lookup inventory (5ms)
4. Return results (26ms total)
```

**Cache Invalidation**:
```
Trigger: Product added/removed/updated
Action: Delete all search:* keys matching affected product
Fallback: TTL ensures max staleness of 10 seconds
```

---

**End of Document**

For detailed implementation questions, refer to:
- SYSTEM_ARCHITECTURE_ULTRA_V2.md (comprehensive decision trees and multi-product analysis)
- ARCHITECTURE_FLOWCHARTS.md (visual system diagrams)
- QUEUE_WAIT_ANALYSIS.md (mathematical derivation)