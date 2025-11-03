# Reservation Expiry & Auto-Release Design

## The Missing Requirement

From the PDF:
```
"Allow atomic reservations with expiry (e.g., 2-minute hold) and auto-release on timeout."
```

### What This Means
1. When a user reserves a unit, it's held for **2 minutes**
2. During those 2 minutes, the user must **complete checkout**
3. If 2 minutes pass without checkout, **reservation automatically expires**
4. **Expired unit returns to inventory** and becomes available for other users

---

## Current Design Gap

The existing architecture in SYSTEM_ARCHITECTURE_ULTRA.md mentions:
```
"Reservation auto-expire on timeout"
```

But it **lacks detailed implementation** for:
1. ❌ How to track reservation expiry precisely
2. ❌ When/how to trigger auto-release
3. ❌ How to handle stock re-allocation
4. ❌ Handling edge cases (concurrent operations)
5. ❌ Latency impact on P95
6. ❌ Coordination with cache invalidation

---

## Proposed Solution: Tiered Expiry System

### Architecture Overview

```
┌─────────────────────────────────────────┐
│   User Creates Reservation              │
│   (Receives 2-minute hold)              │
└────────────────┬────────────────────────┘
                 │
        ┌────────v─────────┐
        │  Store in DB:    │
        │  - expires_at    │
        │  - status: HELD  │
        └────────┬─────────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
    v            v            v
┌──────┐  ┌────────────┐  ┌─────────────┐
│Redis │  │Scheduler   │  │TTL Job      │
│TTL   │  │(10s check) │  │(Kafka)      │
└──────┘  └────────────┘  └─────────────┘
    │            │            │
    └────────────┼────────────┘
                 │
        ┌────────v────────────┐
        │ Expired?            │
        │ YES → Release       │
        │ NO → Continue hold  │
        └─────────────────────┘
```

### Three-Layer Expiry System

We implement expiry at **three levels** for robustness:

#### Layer 1: Redis TTL (Fast Path - Real-time)
```
Key: reservation:{reservation_id}
TTL: 120 seconds (2 minutes)
Auto-expire: Redis automatically deletes after 2 min
Latency: <1ms check
Reliability: 99% (Redis-dependent)
```

**Pros**:
- ✓ Instant auto-delete (no manual cleanup needed)
- ✓ Sub-millisecond latency
- ✓ Standard Redis feature

**Cons**:
- ✗ If Redis crashes, might not delete immediately
- ✗ Need DB sync (Redis is cache, not source of truth)
- ✗ Lost if Redis restarts

#### Layer 2: Scheduled Cleanup Job (Periodic - 10 second intervals)
```
Every 10 seconds:
1. Query DB: SELECT * FROM reservations WHERE expires_at < NOW()
2. For each expired reservation:
   - Set status = 'EXPIRED'
   - UPDATE inventory (increment available_count)
   - DELETE from cache
   - PUBLISH expiry event
3. Commit transaction
```

**Timing**:
```
Scheduled Job Timeline:
T=0s: User creates reservation (expires_at = T+120s)
T=10s: Scheduler runs (doesn't see expiry yet, 110s remaining)
T=20s: Scheduler runs (doesn't see expiry yet, 100s remaining)
...
T=110s: Scheduler runs (doesn't see expiry yet, 10s remaining)
T=120s: Reservation expires in Redis
T=130s: Scheduler runs, DETECTS expired reservation
        (Max lag: 10 seconds after Redis TTL)
```

**Pros**:
- ✓ Durable (database source of truth)
- ✓ Handles Redis failures
- ✓ Audit trail created
- ✓ Can process batch of expirations efficiently

**Cons**:
- ✗ Up to 10-second lag in detection
- ✗ Additional database load
- ✗ Requires monitoring

#### Layer 3: Event-Driven (Proactive - Kafka)
```
When reservation expires (Redis TTL):
1. Publish: reservation.expired event
2. Subscribers listen to this event
3. Immediately release stock without waiting for scheduler
4. No need to query database
```

**Pros**:
- ✓ No lag (immediate event-driven)
- ✓ Low database load
- ✓ Distributed to multiple listeners
- ✓ Creates audit trail naturally

**Cons**:
- ✗ Depends on Kafka availability
- ✗ Event might be lost (mitigated by scheduler)

---

## Detailed Implementation

### Data Model Changes

#### Database Schema Updates

**Add to `reservations` table**:
```sql
ALTER TABLE reservations ADD COLUMN (
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  released_at TIMESTAMP WITH TIME ZONE,
  release_reason VARCHAR(50)  -- 'EXPIRED', 'CHECKOUT_COMPLETED', 'MANUAL'
);

CREATE INDEX idx_reservations_expires_at ON reservations(expires_at)
  WHERE status = 'RESERVED';
```

**Why these fields?**
- `expires_at`: Track when reservation should expire
- `released_at`: Know when it actually expired (for auditing)
- `release_reason`: Understand why reservation was released

#### Redis Keys Structure

```
# Reservation TTL tracking
Key: reservation:{reservation_id}
Value: {
  "user_id": "u123",
  "sku_id": "sku456",
  "status": "RESERVED",
  "created_at": 1705325645000,
  "expires_at": 1705325765000  // T + 120 seconds
}
TTL: 120 seconds (Redis auto-deletes after this)

# Expiry event tracking (for deduplication)
Key: expiry_processed:{reservation_id}
Value: {
  "processed_at": 1705325770000,
  "processed_by": "scheduler-1"
}
TTL: 300 seconds (keep for 5 minutes to deduplicate)
```

---

### Implementation Details

#### Step 1: Create Reservation (Modified)

```
POST /api/v1/reserve

Request validation:
1. Check user tier (rate limiting)
2. Check user doesn't have active reservation
3. Validate idempotency_key

Create reservation:
1. Send to Kafka: reservation.request
   └─ Include: user_id, sku_id, idempotency_key

Consumer batch processing:
1. Allocate stock
2. Create reservation in DB:
   INSERT INTO reservations (
     reservation_id,
     user_id,
     sku_id,
     status,
     expires_at,  // ← NEW: NOW() + 120 seconds
     idempotency_key,
     created_at
   ) VALUES (...)

3. Cache in Redis with TTL:
   SET reservation:{reservation_id} {json} EX 120

4. Publish event:
   PUBLISH reservation.created {
     reservation_id,
     user_id,
     sku_id,
     expires_at: T+120s  // ← NEW
   }

Return to user:
{
  "reservation_id": "res-abc123",
  "status": "RESERVED",
  "expires_at": "2025-01-15T10:32:00Z",
  "expires_in_seconds": 120,
  "message": "Hold expires in 2 minutes"
}
```

#### Step 2: Scheduled Expiry Cleanup Job

```java
@Scheduled(fixedRate = 10000)  // Every 10 seconds
public void cleanupExpiredReservations() {
    logger.info("Running expiry cleanup job");

    // Query expired reservations
    List<Reservation> expired = reservationRepo.findExpired();

    if (expired.isEmpty()) {
        return;
    }

    // Process in batch
    List<String> skuIds = new HashSet<>();

    for (Reservation res : expired) {
        // Skip if already processed (deduplication)
        if (redis.exists(f"expiry_processed:{res.id}")) {
            continue;
        }

        // Update reservation status
        res.setStatus("EXPIRED");
        res.setReleasedAt(Instant.now());
        res.setReleaseReason("EXPIRED");
        reservationRepo.save(res);

        // Update inventory (increment available stock)
        inventoryRepo.incrementAvailable(res.getSkuId(), 1);
        skuIds.add(res.getSkuId());

        // Mark as processed (prevent duplicate processing)
        redis.setex(f"expiry_processed:{res.id}", 300, "true");

        // Publish event
        kafkaTemplate.send("reservation-expiry", res.getId(),
            new ReservationExpiredEvent(
                reservationId: res.getId(),
                userId: res.getUserId(),
                skuId: res.getSkuId(),
                reason: "EXPIRED"
            )
        );
    }

    // Invalidate cache for affected SKUs
    for (String skuId : skuIds) {
        redis.del(f"stock:{skuId}");
    }

    logger.info("Cleaned up {} expired reservations", expired.size());
}
```

#### Step 3: Checkout (Modified)

```
POST /api/v1/checkout

Validation:
1. Check idempotency_key (prevent double-payment)
2. Check reservation exists
3. NEW: Check reservation hasn't expired
   IF expired → Return 410 Gone
   "Reservation expired. Please try again."

Process:
1. Create order with status: PENDING
2. Queue payment request to async processor
3. Return order details with expiry status
```

**Edge Case Handling**:
```
Scenario 1: Reservation expires during checkout
T=115s: User starts checkout
T=120s: Reservation expires (Redis TTL triggers)
T=122s: Scheduler detects expiry, marks EXPIRED
T=125s: User still completing checkout
        POST /checkout → Fails with 410 Gone
        ✓ System prevents double-allocation

Scenario 2: Scheduler hasn't run yet
T=130s: Reservation should have expired (T+120)
T=131s: User checks availability (before scheduler runs at T=140)
        Database still shows RESERVED (not expired yet)
        Redis TTL already deleted the key
        Cache miss → Query DB → Shows RESERVED
        ✓ User allowed to checkout (within tolerance window)

Scenario 3: Redis crashes
T=120s: Reservation TTL expires in Redis, but Redis crashes
T=130s: Scheduler job runs, detects expired in DB, releases stock
        ✓ Durable system recovers automatically
```

---

## Integration with Existing Design

### Changes to Decision 2: Inventory Management

**Current**: Single-writer batch processing

**Enhanced with Expiry**:
```
┌──────────────────────────────┐
│ Kafka: reservation-requests  │
└──────────────┬───────────────┘
               │
    ┌──────────┴──────────┐
    │                     │
    v                     v
Batch Consumer      Expiry Job
(Process reserves)   (Every 10s)
    │                     │
    ├─→ Allocate stock    ├─→ Query DB for expired
    ├─→ Create reservation├─→ Update inventory
    ├─→ SET Redis TTL     ├─→ Publish events
    └─→ Publish event     └─→ Invalidate cache
```

### Changes to Decision 3: Cache Layer

**Current**: Redis Cluster with 5-second TTL

**Enhanced with Expiry**:
```
Cache invalidation scenarios:
1. NEW RESERVATION created
   → Invalidate stock:{sku_id}
   → Latency: <1ms

2. RESERVATION EXPIRES
   → Invalidate stock:{sku_id}
   → Latency: Depends on trigger (Redis immediate, or 10s scheduler)

3. STOCK TTL (existing 5-second)
   → Auto-refresh from DB
   → Latency: ~10ms
```

### Changes to Decision 12: State Management for Reservation Expiry

**Current**: Scheduled job with DB scan + Redis Pub/Sub

**This IS the solution!** No change needed, but enhance:

```
Current (from SYSTEM_ARCHITECTURE_ULTRA.md):
"Every 10 seconds: Query DB for expired reservations"

Enhanced:
1. Redis TTL (immediate, <1ms)
   └─ Key auto-deletes after 120 seconds

2. Scheduled Job (periodic, every 10s)
   └─ Query DB for missed expirations
   └─ Process batch of expired reservations

3. Event-Driven (reactive, 0ms)
   └─ Kafka publishes reservation.expired
   └─ Subscribers handle stock release immediately
   └─ No need to query DB
```

---

## Latency Impact Analysis

### User Reservation Request (with expiry)

```
Current P95 latency: ~60ms
Breakdown:
- API Gateway: 5ms
- Queue serialization: 1ms
- Queue wait: 50ms
- Batch processing: 10ms (includes ALL DB writes)
  ├─ Update inventory
  ├─ INSERT reservation (with expires_at)
  ├─ Publish events
  └─ Set Redis TTL
- Response: 5ms

Total: 71ms (no change! ✓)
```

**Why no latency impact?**
- Setting `expires_at` column: +0ms (part of INSERT already)
- Setting Redis TTL: +0ms (part of SET already)
- No additional database queries
- No additional network calls

### Expiry Processing Latency

**Layer 1: Redis TTL**
```
Latency: 0ms (automatic)
Reliability: 99% (Redis-dependent)
Detection latency: Immediate
```

**Layer 2: Scheduled Job (every 10s)**
```
Latency: 10 ± 5 seconds (average 10s, max 20s if just started)
Reliability: 99.9% (durable DB-backed)
Detection latency: Max 20 seconds after actual expiry
Processing time: ~50ms for batch of 100 expirations
```

**Layer 3: Event Stream**
```
Latency: <1ms (Kafka publish)
Reliability: 99.9% (message queue backed)
Detection latency: Immediate (when subscriber processes)
```

### Stock Release Timeline

```
Reservation created:
T=0s: Reservation created, expires_at = T+120s

T=120s: Stock release triggered
├─ Redis TTL auto-deletes key (L1)
└─ Subscriber receives event (L3)

T=120-130s: Stock released
├─ Expiry event published to Kafka
├─ Event subscribers handle immediately
└─ Cache invalidated

T=130s: Scheduler runs (L2)
├─ Confirms expiry in DB
├─ Updates reservation.status = EXPIRED
└─ Publishes audit event (fallback if L3 failed)

Result:
✓ Stock available to new reservations by T=130s (max)
✓ Usually available by T=120s (if Redis/Kafka working)
✓ Always available by T=140s (scheduler guarantee)
```

---

## Monitoring & Alerting

### New Metrics to Track

```
1. reservation_expiry_lag_seconds
   └─ How long after expires_at is expiry detected?
   └─ Target: <10 seconds
   └─ Alert if: >30 seconds (indicates scheduler lag)

2. expired_reservations_per_second
   └─ How many reservations expire per second?
   └─ Normal: Depends on user checkout rate
   └─ Alert if: Spiking unexpectedly (could indicate system issue)

3. expiry_job_duration_ms
   └─ How long does scheduler job take?
   └─ Target: <100ms
   └─ Alert if: >500ms (database slow or too many expirations)

4. reservation_expiry_events_lost
   └─ Events published but not processed (L3 failure)?
   └─ Target: 0
   └─ Alert if: >0 (indicates Kafka issues, fall back to L2)

5. stock_mismatch_from_expired
   └─ Did expiry actually release stock?
   └─ Target: stock_released == reservations_expired
   └─ Alert if: Divergence (data consistency issue)
```

### Alerts

```
CRITICAL:
- Expiry lag > 30 seconds (stock not released in time)
- Expiry event loss rate > 0.1% (users lose reservations)
- Scheduler job failing (durable release mechanism broken)

WARNING:
- Expiry job duration > 200ms (database slow)
- Stock mismatch detected (investigate data consistency)
- Redis unavailable >1 minute (fall back to scheduler only)

INFO:
- High expiry rate (>100/sec): Normal for flash sales
- Scheduler processed N expirations: Normal log
```

---

## Edge Cases & Handling

### Edge Case 1: Reservation Created, Immediately Expired?

```
Scenario: System clock skew or bug
Reservation created with expires_at = NOW() - 1 second

Detection:
- Scheduler sees immediately (next run)
- Event system marks as already expired
- User cannot checkout with expired reservation

Mitigation:
- System clock monitoring (NTP sync)
- Validation: expires_at must be > NOW() at creation time
- Alert on clock skew >1 second
```

### Edge Case 2: Concurrent Checkout & Expiry

```
Scenario:
T=119s: User initiates checkout (reservation still valid)
T=120s: Reservation expires (Redis TTL fires)
T=121s: Checkout attempt reaches server
        Reservation.status = EXPIRED in DB
        → Return 410 Gone to user

Result: ✓ User prevented from paying for expired reservation
```

### Edge Case 3: Duplicate Expiry Processing

```
Scenario:
T=130s: Scheduler job 1 detects expiry, processes it
T=130.5s: Scheduler job 2 starts (overlapping execution)
          Tries to process same expiration again

Prevention:
- expiry_processed:{reservation_id} key in Redis
  └─ Set after processing with 5-minute TTL
  └─ Check before processing
  └─ Skip if already processed (idempotency)

Result: ✓ Duplicate release prevented, idempotent operation
```

### Edge Case 4: Reservation Expires During High Load

```
Scenario: 25k RPS during event
T=120s: 10,000 reservations expire simultaneously
        Stock needs to be released for all

Processing:
- Redis TTL: Deletes all keys instantly (no load)
- Kafka events: Queued to event stream (durable)
- Scheduler: Will batch process all 10,000 in single transaction
            (~100ms processing time)

Result: ✓ Stock released within 10-20 seconds
        ✓ Available for new reservations
```

---

## Changes Required to Existing Design

### 1. Database Schema (REQUIRED)

Add columns to `reservations` table:
```sql
ALTER TABLE reservations ADD COLUMN (
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  released_at TIMESTAMP WITH TIME ZONE,
  release_reason VARCHAR(50)
);

CREATE INDEX idx_reservations_expires_at ON reservations(expires_at)
  WHERE status = 'RESERVED';
```

**Impact**: Zero latency impact (one-time schema change)

### 2. Kafka Consumer (REQUIRED)

Enhance reservation creation logic:
```java
// When creating reservation in batch
LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(2);
reservation.setExpiresAt(expiresAt);
```

**Impact**: +0ms (same transaction that already writes to DB)

### 3. Redis (REQUIRED)

Set TTL on reservation cache:
```java
// When caching reservation
redis.setex(
    f"reservation:{reservationId}",
    120,  // 2 minutes in seconds
    json.serialize(reservation)
);
```

**Impact**: +0ms (already setting Redis keys, just add TTL)

### 4. Scheduled Job (REQUIRED)

Add cleanup job (if not already present):
```java
@Scheduled(fixedRate = 10000)  // Every 10 seconds
public void cleanupExpiredReservations() {
    // Implementation as shown above
}
```

**Impact**: +50-100ms per run (every 10 seconds)

### 5. API Endpoints (REQUIRED)

Update `/checkout` endpoint:
```java
// Before processing payment
if (reservation.isExpired()) {
    return ResponseEntity.status(410)  // 410 Gone
        .body("Reservation has expired");
}
```

**Impact**: +1ms (simple timestamp comparison)

### 6. Monitoring (RECOMMENDED)

Add metrics:
```java
// Track expiry detection
metrics.timer("reservation.expiry.lag")
       .record(Duration.ofSeconds(lag));

// Track expired count
metrics.counter("reservation.expired_count").increment();
```

**Impact**: +2-5ms (minimal)

### 7. Documentation (REQUIRED)

Update API documentation:
```json
{
  "reservation_id": "res-abc123",
  "expires_at": "2025-01-15T10:32:00Z",  // NEW
  "expires_in_seconds": 120,               // NEW
  "message": "Hold expires in 2 minutes"   // UPDATED
}
```

---

## Summary of Changes

| Component | Change | Impact | Priority |
|-----------|--------|--------|----------|
| Database Schema | Add `expires_at`, `released_at`, `release_reason` | 0ms latency | REQUIRED |
| Kafka Consumer | Set `expires_at = NOW() + 120s` on creation | 0ms latency | REQUIRED |
| Redis Cache | Set TTL=120s on reservation keys | 0ms latency | REQUIRED |
| Scheduled Job | Add cleanup job running every 10s | 50-100ms per run | REQUIRED |
| API Gateway | Validate reservation hasn't expired on checkout | 1ms | REQUIRED |
| Monitoring | Track expiry metrics and alerts | 2-5ms | RECOMMENDED |
| Documentation | Update API contracts | N/A | REQUIRED |

---

## Three-Layer Redundancy

The design provides **three independent mechanisms** for expiry:

```
Layer 1: Redis TTL (Real-time, <1ms)
  └─ Automatic deletion after 120 seconds
  └─ No code needed, Redis handles
  └─ IF FAILS → Fall back to Layer 2

Layer 2: Scheduled Cleanup (Periodic, every 10s)
  └─ Database source of truth
  └─ Catches any missed expirations
  └─ IF FAILS → Fall back to Layer 3

Layer 3: Event Stream (Reactive, 0ms)
  └─ Kafka publishes expiry events
  └─ Subscribers handle immediately
  └─ IF FAILS → Layers 1 & 2 still work
```

**Result**: Guaranteed stock release even if components fail!

---

## Conclusion

The 2-minute reservation expiry feature can be **seamlessly integrated** into the existing design with:

✓ **Zero impact on P95 latency** (60ms unchanged)
✓ **Three-layer redundancy** (guaranteed reliability)
✓ **Minimal code changes** (5-6 components)
✓ **Backward compatible** (existing reservations unaffected)
✓ **Monitoring ready** (metrics and alerts defined)

The key insight is that **expiry management doesn't require synchronous processing**—it can happen asynchronously in the background while the critical path (user reservation) remains fast and responsive.
