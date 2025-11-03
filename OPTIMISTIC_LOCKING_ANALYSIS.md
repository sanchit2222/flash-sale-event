# Optimistic Locking P95 Latency Analysis

## The Question
For optimistic locking with 25k RPS writes to a single SKU, what is the **P95 latency**?

The document shows:
```
Expected retries per request: 25 retries average
Total database attempts: 25,000 × 25 = 625,000 attempts/second
P99 latency: 25 retries × 10ms = 250ms (violates P95 ≤120ms)
```

But it only states **P99 = 250ms**. Let's calculate **P95**.

---

## Understanding Retry Distribution

### Assumption from the Document
```
Concurrent requests: 25,000 / second
Lock-free window: ~1ms (time between updates)
Competing requests in window: 25 requests
Success rate: 1/25 = 4%
Failure rate: 96%

Expected retries per request: 25 retries average
```

This means requests follow a **geometric distribution** where:
- Probability of success on any attempt: p = 4% = 0.04
- Probability of failure: q = 96% = 0.96
- Expected attempts = 1/p = 1/0.04 = 25 ✓

---

## Retry Distribution Math

For a geometric distribution (number of trials until first success):

**P(X ≤ n) = 1 - (1-p)^n**

Where:
- X = number of retry attempts
- p = success probability = 0.04
- (1-p) = failure probability = 0.96

### Calculating Different Percentiles

**P50 (Median - 50th percentile)**:
```
50% of requests succeed within X attempts where:
1 - (0.96)^X = 0.50
(0.96)^X = 0.50
X × ln(0.96) = ln(0.50)
X × (-0.0408) = -0.693
X = 17 attempts
```
**P50 latency = 17 retries × 10ms = 170ms**

Wait, that's already too high! Let me recalculate...

**Correct approach**:
- Expected value E[X] = 1/p = 1/0.04 = 25 ✓
- But we want median (P50), not expected value

**For P50**:
```
1 - (0.96)^X = 0.50
(0.96)^X = 0.50
X = ln(0.50) / ln(0.96)
X = -0.693 / -0.0408
X ≈ 17 attempts
P50 latency ≈ 170ms
```

This is already **violating the 120ms SLO at the 50th percentile!**

### Calculating P95

**P95 (95th percentile)**:
```
1 - (0.96)^X = 0.95
(0.96)^X = 0.05
X = ln(0.05) / ln(0.96)
X = -2.996 / -0.0408
X ≈ 73 attempts
```

**P95 latency = 73 retries × 10ms = 730ms**

### Calculating P99 (as stated in document)

**P99 (99th percentile)**:
```
1 - (0.96)^X = 0.99
(0.96)^X = 0.01
X = ln(0.01) / ln(0.96)
X = -4.605 / -0.0408
X ≈ 113 attempts
```

**P99 latency = 113 retries × 10ms = 1,130ms ≈ 1.13 seconds**

The document states P99 = 250ms, which assumes:
```
P99 = 25 retries × 10ms = 250ms
```

This would only be true if P99 was around 25 attempts, but with 96% failure rate, 25 is the **expected (mean) value**, not P99.

---

## Correction: Why Document's P99 is Wrong

The document calculation:
```
P99 latency: 25 retries × 10ms = 250ms
```

This assumes P99 = average, which is **incorrect**.

**Correct calculation**:
```
P50 latency: ~170ms (50% of requests)
P95 latency: ~730ms (95% of requests)
P99 latency: ~1,130ms (99% of requests)
```

---

## Answer to Your Question: P95 Latency

**P95 Latency for Optimistic Locking = 730ms**

This is because:
1. Each individual request has 4% success rate
2. 95% of requests need up to 73 retry attempts to succeed
3. Each attempt takes ~10ms (database query + conflict detection)
4. 73 × 10ms = 730ms

---

## Why This Violates the SLO

The requirement is:
```
P95 reservation latency ≤ 120ms
```

But optimistic locking achieves:
```
P95 latency = 730ms (6x worse than SLO)
P99 latency = 1,130ms (9x worse than SLO)
```

Even P50 = 170ms exceeds the 120ms SLO!

---

## Comparison with Other Approaches

### 1. Pessimistic Locking (Row Locks)
```
Serializes all access, processes one at a time
P95 latency: 2.4 minutes (4 minutes at P99)
Verdict: Much worse, serialization is deadly
```

### 2. Single-Writer Pattern with Batching
```
Batch size: 250 requests per 10ms
Queue wait: 0-50ms (depends on arrival time)
Processing: 10ms
P95 latency: ~50-60ms (within budget!)
Verdict: Winner ✓
```

### 3. Optimistic Locking (This Analysis)
```
Retry storm with 96% failure rate
P50 latency: 170ms (already exceeds 120ms SLO!)
P95 latency: 730ms
P99 latency: 1,130ms
Verdict: Far exceeds budget ✗
```

---

## Key Insight: Why Retry Rate Matters

The critical insight is that **high contention creates exponential retry distribution**:

```
Scenario: 25k RPS to single SKU

Optimistic Locking:
- 25 concurrent writers
- Each succeeds only if others fail
- Retry rate: 96% per attempt
- Result: Exponential tail latency

Single-Writer Pattern:
- 25 writers queued FIFO
- Each completes without contention
- No retries needed
- Result: Predictable latency
```

**The difference**: One fights contention (loses), one avoids it (wins).

---

## Mathematical Conclusion

**For optimistic locking under 25k RPS contention**:

| Percentile | Attempts Needed | Latency | SLO Status |
|-----------|-----------------|---------|-----------|
| P50 | 17 | 170ms | ❌ Violates |
| P95 | 73 | 730ms | ❌ Violates |
| P99 | 113 | 1,130ms | ❌ Violates |

**Note**: The document's statement of "P99 latency: 25 retries × 10ms = 250ms" is mathematically incorrect. The document conflates the **expected value (mean)** with the **P99 percentile**.

---

## Why This Matters for Your System Design

This analysis proves why the ULTRA document was so emphatic about the single-writer pattern:

**Optimistic locking sounds good in theory** (no locks!), but under extreme contention (96% conflict rate), you get:
- P50 latency = 170ms (already exceeds SLO at 50% of traffic!)
- Massive retry amplification (625,000 DB attempts for 25,000 requests)
- Unpredictable user experience (some users wait 7+ seconds)

**Single-writer pattern is the only viable solution** because it:
- Eliminates retries completely
- Provides P95 latency = 50-60ms (within budget)
- Predictable fairness (FIFO queue)
- Database handles batches in ~10ms each

This is why it's the **selected solution** in the architecture.
