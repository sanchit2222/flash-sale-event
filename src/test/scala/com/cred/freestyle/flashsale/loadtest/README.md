# Flash Sale Load Testing Guide

## Overview

This directory contains Gatling-based load tests for the Flash Sale reservation system. The tests validate the system's ability to handle high-throughput scenarios with Kafka batch processing.

## System Requirements Under Test

From `FLASH_SALE_SOLUTION_DESIGN.md`:

| Requirement | Target | Criticality |
|------------|--------|-------------|
| Throughput | 25,000 RPS | CRITICAL |
| P95 Latency | < 120ms | CRITICAL |
| P99 Latency | < 200ms | HIGH |
| Success Rate | > 90% | HIGH |
| Zero Oversell | 100% | CRITICAL |

## Prerequisites

### 1. Install Gatling Maven Plugin

Add to your `pom.xml`:

```xml
<plugin>
    <groupId>io.gatling</groupId>
    <artifactId>gatling-maven-plugin</artifactId>
    <version>4.6.0</version>
</plugin>
```

### 2. Infrastructure Setup

Before running 25K RPS tests, ensure:

**Kubernetes Cluster:**
- Min 10 nodes (4 vCPU, 16GB RAM each)
- Autoscaling enabled
- Network bandwidth: 10 Gbps+

**Database:**
- PostgreSQL with connection pooling (min 100 connections)
- Read replicas configured
- Query performance optimized

**Kafka:**
- 10 partitions for `reservation-requests` topic
- Replication factor: 3
- Min 3 brokers

**Redis:**
- Cluster mode with 3 masters + 3 replicas
- Memory: 16GB+ total

**Load Test Runner:**
- Min 16 vCPU, 32GB RAM
- Same region as application
- Direct network path (no internet routing)

## Test Scenarios

### 1. Baseline Test (Default)

Quick smoke test with low load.

```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.cred.freestyle.flashsale.loadtest.ReservationLoadTest
```

**Expected:**
- 50 concurrent users
- P95 < 500ms
- 95%+ success rate

**Duration:** 2 minutes

---

### 2. 25K RPS Validation Test (CRITICAL)

Validates the core system requirement of 25K RPS with P95 < 120ms.

```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.cred.freestyle.flashsale.loadtest.ReservationLoadTest \
  -DtestType=25k-rps \
  -DbaseUrl=https://your-production-cluster.com
```

**Test Profile:**
```
0-30s:   Ramp 0 → 5K RPS
30-75s:  Ramp 5K → 15K RPS
75-120s: Ramp 15K → 25K RPS
120s-30m: Sustain 25K RPS (flash sale duration)
```

**Success Criteria:**
- ✅ Throughput: >= 23,750 RPS (95% of target)
- ✅ P95 latency: <= 120ms
- ✅ P99 latency: <= 200ms
- ✅ Mean latency: <= 80ms
- ✅ Success rate: >= 90%

**Duration:** 32 minutes

**CRITICAL:** This test must pass before production deployment.

---

### 3. Peak Load Test

Simulates flash sale start with sudden traffic spike.

```bash
mvn gatling:test \
  -DtestType=peak \
  -DbaseUrl=https://your-staging.com
```

**Test Profile:**
- Immediate spike: 100 concurrent users
- Ramp to 200 users over 15 seconds
- Sustain 30 RPS for 60 seconds

**Expected:**
- Graceful handling of spike
- Queue depth manageable
- No crashes

**Duration:** 2 minutes

---

### 4. Stress Test

Finds the breaking point of the system.

```bash
mvn gatling:test \
  -DtestType=stress \
  -DbaseUrl=https://your-staging.com
```

**Test Profile:**
- Extreme concurrent load (500+ users at once)
- Push system beyond capacity

**Expected:**
- System degrades gracefully
- Error rates increase but system remains responsive
- No data corruption

**Duration:** 1 minute

**⚠️ WARNING:** Only run on staging environment.

---

### 5. Soak Test

Validates system stability under sustained load.

```bash
mvn gatling:test \
  -DtestType=soak \
  -DbaseUrl=https://your-staging.com
```

**Test Profile:**
- 20 RPS sustained for 10 minutes

**Expected:**
- No memory leaks
- No connection pool exhaustion
- Stable latency over time

**Duration:** 11 minutes

---

### 6. Spike Test

Tests resilience to sudden traffic spikes.

```bash
mvn gatling:test \
  -DtestType=spike \
  -DbaseUrl=https://your-staging.com
```

**Test Profile:**
```
0-60s:   Normal load (10 RPS)
60-120s: Sudden spike (500 concurrent users)
120-180s: Return to normal (10 RPS)
```

**Expected:**
- System recovers after spike
- No cascading failures

**Duration:** 3 minutes

---

### 7. Capacity Test

Gradually increases load to find optimal capacity.

```bash
mvn gatling:test \
  -DtestType=capacity \
  -DbaseUrl=https://your-staging.com
```

**Test Profile:**
- Step-wise increase: 10 → 20 → 50 → 100 → 200 RPS
- Each step: 30 seconds

**Expected:**
- Identify inflection point where latency degrades
- Determine optimal pod count

**Duration:** 2.5 minutes

---

### 8. Combined Scenarios

Realistic mix of different user behaviors.

```bash
mvn gatling:test \
  -DtestType=combined \
  -DbaseUrl=https://your-staging.com
```

**Test Profile:**
- 50 users creating reservations
- 30 users creating + cancelling
- 20 users attempting duplicates

**Expected:**
- All scenarios coexist smoothly
- No interference between scenarios

**Duration:** 3 minutes

---

## Advanced Configuration

### Custom Parameters

```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.cred.freestyle.flashsale.loadtest.ReservationLoadTest \
  -DbaseUrl=https://custom-url.com \
  -DtestType=25k-rps \
  -DnormalLoad=100 \
  -DpeakLoad=500 \
  -DextremeLoad=1000
```

### Environment-Specific Runs

**Staging:**
```bash
mvn gatling:test -DbaseUrl=https://staging.flashsale.com
```

**Production (During Maintenance Window):**
```bash
mvn gatling:test \
  -DbaseUrl=https://api.flashsale.com \
  -DtestType=baseline
```

**Local Development:**
```bash
mvn gatling:test -DbaseUrl=http://localhost:8080
```

## Monitoring During Tests

### 1. Real-Time Metrics

Monitor these CloudWatch metrics during load tests:

```bash
# Terminal 1: Watch throughput
watch -n 1 'aws cloudwatch get-metric-statistics \
  --namespace FlashSale \
  --metric-name RequestCount \
  --start-time $(date -u -d "5 minutes ago" +"%Y-%m-%dT%H:%M:%S") \
  --end-time $(date -u +"%Y-%m-%dT%H:%M:%S") \
  --period 60 \
  --statistics Sum'

# Terminal 2: Watch P95 latency
watch -n 1 'aws cloudwatch get-metric-statistics \
  --namespace FlashSale \
  --metric-name ReservationLatency \
  --start-time $(date -u -d "5 minutes ago" +"%Y-%m-%dT%H:%M:%S") \
  --end-time $(date -u +"%Y-%m-%dT%H:%M:%S") \
  --period 60 \
  --statistics p95'
```

### 2. Kafka Consumer Lag

```bash
kubectl exec -it kafka-0 -- kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group inventory-batch-consumer \
  --describe
```

**Acceptable Lag:**
- < 10,000 messages: Healthy
- 10,000 - 50,000: Warning
- > 50,000: Critical

### 3. Database Connections

```bash
kubectl exec -it postgres-0 -- psql -U postgres -c \
  "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';"
```

**Acceptable Connections:**
- < 80: Healthy
- 80 - 95: Warning
- > 95: Critical

### 4. Pod CPU/Memory

```bash
kubectl top pods -n flash-sale --sort-by=cpu
```

## Interpreting Results

### Gatling Report

After test completion, Gatling generates an HTML report:

```
target/gatling/reservationloadtest-{timestamp}/index.html
```

**Key Sections:**
1. **Global Information:** Overall stats
2. **Request Details:** Per-request breakdown
3. **Distribution:** Latency percentiles
4. **Timeline:** RPS and latency over time

### Success Criteria Checklist

For **25K RPS test**, verify:

- [ ] Global RPS >= 23,750
- [ ] P95 latency <= 120ms
- [ ] P99 latency <= 200ms
- [ ] Success rate >= 90%
- [ ] No database errors
- [ ] Kafka consumer lag < 10,000
- [ ] No pod restarts
- [ ] No OOM errors

### Failure Analysis

If test fails, check:

1. **High Latency (P95 > 120ms)**
   - Database slow query log
   - Kafka consumer lag
   - Network latency
   - Pod CPU throttling

2. **High Error Rate (> 10% failures)**
   - Application logs
   - Database connection pool exhaustion
   - Kafka broker issues
   - Rate limiting triggered

3. **Low Throughput (< 23,750 RPS)**
   - Insufficient pods
   - Database bottleneck
   - Network bandwidth limit
   - Load balancer limit

## Troubleshooting

### Issue: Test hangs or times out

**Solution:**
```bash
# Increase Gatling timeout
export GATLING_OPTS="-Dgatling.http.ahc.requestTimeout=60000"
mvn gatling:test -DtestType=25k-rps
```

### Issue: Connection refused

**Solution:**
Check application health:
```bash
curl -f http://localhost:8080/actuator/health || echo "Service down"
```

### Issue: Out of memory during test

**Solution:**
Increase JVM heap for Gatling:
```bash
export MAVEN_OPTS="-Xms2g -Xmx8g"
mvn gatling:test -DtestType=25k-rps
```

### Issue: Metrics not recorded

**Solution:**
Verify CloudWatch IAM permissions:
```bash
aws cloudwatch put-metric-data --namespace Test --metric-name TestMetric --value 1
```

## Best Practices

1. **Run tests in sequence** (not parallel) to avoid interference
2. **Clear Kafka topics** between test runs for clean state
3. **Monitor infrastructure** during entire test duration
4. **Run 25K RPS test multiple times** to ensure consistency
5. **Test on production-like environment** (same instance types, network)
6. **Coordinate with team** before running load tests
7. **Document results** in test reports
8. **Compare results** over time to detect regressions

## Test Data Management

### Before Tests

```bash
# Create test product with 10M inventory
curl -X POST http://localhost:8080/api/v1/admin/products \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": "SKU-LOAD-TEST-001",
    "name": "Load Test Product",
    "totalInventory": 10000000,
    "flashSalePrice": 999.99,
    "isActive": true
  }'
```

### After Tests

```bash
# Clean up test data
psql -h localhost -U postgres -d flashsale -c \
  "DELETE FROM reservations WHERE user_id LIKE 'user-perf-%';"

psql -h localhost -U postgres -d flashsale -c \
  "DELETE FROM user_purchase_tracking WHERE user_id LIKE 'user-perf-%';"

# Reset inventory
psql -h localhost -U postgres -d flashsale -c \
  "UPDATE inventory SET reserved_count = 0, available_count = total_count \
   WHERE sku_id = 'SKU-LOAD-TEST-001';"
```

## Continuous Integration

### GitHub Actions Example

```yaml
name: Load Test
on:
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM UTC
  workflow_dispatch:

jobs:
  load-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run 25K RPS Test
        run: |
          mvn gatling:test \
            -DtestType=25k-rps \
            -DbaseUrl=${{ secrets.STAGING_URL }}
      - name: Upload Report
        uses: actions/upload-artifact@v3
        with:
          name: gatling-report
          path: target/gatling/*/index.html
```

## Support

For questions or issues:
- Slack: #flash-sale-team
- Email: flash-sale-team@company.com
- Runbook: [Confluence Link]

## References

- [Gatling Documentation](https://gatling.io/docs/)
- [FLASH_SALE_SOLUTION_DESIGN.md](../../../../../../../../FLASH_SALE_SOLUTION_DESIGN.md)
- [KAFKA_BATCH_PROCESSING_MIGRATION.md](../../../../../../../../KAFKA_BATCH_PROCESSING_MIGRATION.md)
