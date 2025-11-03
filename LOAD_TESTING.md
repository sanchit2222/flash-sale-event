# Flash Sale Load Testing Guide

This guide provides comprehensive instructions for running load tests on the Flash Sale application using Gatling.

## Overview

The load testing suite consists of multiple test scenarios designed to validate system performance under various load conditions:

1. **FlashSaleLoadTest** - Comprehensive multi-scenario load test
2. **ReservationLoadTest** - Focused test on the critical reservation endpoint

## Prerequisites

- Java 17+
- Maven 3.8+
- Running instance of Flash Sale application
- Sufficient test inventory configured in the database

## Test Scenarios

### FlashSaleLoadTest Scenarios

| Scenario | Description | Use Case |
|----------|-------------|----------|
| `smoke` | Light load with 10-15 users | Quick validation after deployment |
| `flashsale` | High concurrent load simulation | Flash sale event start |
| `purchase` | Complete end-to-end purchase flow | User journey testing |
| `mixed` | Combination of read/write operations | Realistic workload |
| `stress` | Extreme concurrent load | System limits testing |
| `endurance` | Sustained load over extended period | Stability and memory leak detection |
| `full` | All scenarios combined | Comprehensive performance test |

### ReservationLoadTest Scenarios

| Test Type | Description | Load Pattern |
|-----------|-------------|--------------|
| `baseline` | Normal load performance | 50 users, steady state |
| `peak` | Flash sale start simulation | 100-300 concurrent users |
| `stress` | Beyond capacity testing | 500+ concurrent users |
| `soak` | 10-minute sustained load | Continuous steady load |
| `spike` | Sudden traffic spike | Gradual → spike → gradual |
| `capacity` | Incremental load increase | Find breaking point |
| `combined` | Multiple scenarios together | Real-world mixed behavior |

## Running Load Tests

### Quick Start

#### Run default mixed workload test:
```bash
mvn gatling:test
```

#### Run specific FlashSaleLoadTest scenario:
```bash
mvn gatling:test -DscenarioName=flashsale
```

#### Run specific ReservationLoadTest:
```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.cred.freestyle.flashsale.loadtest.ReservationLoadTest \
  -DtestType=peak
```

### Advanced Configuration

#### Custom base URL:
```bash
mvn gatling:test -DbaseUrl=http://staging.example.com:8080
```

#### Custom load parameters:
```bash
mvn gatling:test \
  -DscenarioName=flashsale \
  -DrampUsers=500 \
  -DsustainnedUsers=200 \
  -DtestDuration=300
```

#### Reservation test with custom load:
```bash
mvn gatling:test \
  -Dgatling.simulationClass=com.cred.freestyle.flashsale.loadtest.ReservationLoadTest \
  -DtestType=stress \
  -DextremeLoad=1000
```

### Test Data Setup

Before running load tests, ensure test data is properly configured:

```sql
-- Create test products with sufficient inventory
INSERT INTO products (sku_id, name, base_price, flash_sale_price, total_inventory, is_active)
VALUES
  ('SKU-LOAD-001', 'Load Test Product 1', 1000.00, 500.00, 10000, true),
  ('SKU-LOAD-002', 'Load Test Product 2', 2000.00, 1000.00, 10000, true),
  ('SKU-LOAD-003', 'Load Test Product 3', 1500.00, 750.00, 10000, true);

-- Create corresponding inventory
INSERT INTO inventory (inventory_id, sku_id, total_count, reserved_count, sold_count, available_count)
VALUES
  (gen_random_uuid(), 'SKU-LOAD-001', 10000, 0, 0, 10000),
  (gen_random_uuid(), 'SKU-LOAD-002', 10000, 0, 0, 10000),
  (gen_random_uuid(), 'SKU-LOAD-003', 10000, 0, 0, 10000);
```

## Performance Targets

### Response Time Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| P50 (Median) | < 200ms | Half of requests |
| P95 | < 500ms | 95% of requests |
| P99 | < 1000ms | 99% of requests |
| P99.9 | < 2000ms | 99.9% of requests |
| Max | < 5000ms | Slowest request |

### Throughput Targets

| Operation | Target RPS | Notes |
|-----------|-----------|-------|
| Product Browse | 500+ | Read-heavy, cacheable |
| Create Reservation | 200+ | Write-intensive, critical path |
| Checkout | 100+ | Transactional, complex |
| Mixed Workload | 300+ | Realistic combination |

### Reliability Targets

- **Success Rate**: > 95% under normal load
- **Success Rate**: > 70% under stress load
- **Error Rate**: < 5% under normal load
- **Zero Overselling**: No inventory count violations

## Interpreting Results

### Gatling Reports

After test execution, Gatling generates HTML reports:

```
target/gatling/flashsaleloadtest-{timestamp}/index.html
```

#### Key Metrics to Analyze:

1. **Response Time Distribution**
   - Check if P95/P99 meet targets
   - Identify response time outliers
   - Look for degradation patterns

2. **Requests per Second**
   - Verify throughput meets expectations
   - Check for throttling indicators
   - Identify bottlenecks

3. **Success/Failure Rates**
   - Analyze HTTP status code distribution
   - 201: Successful reservations
   - 409: Conflict (expected under high contention)
   - 500: Server errors (investigate)

4. **Concurrent Users**
   - Verify load ramp-up is smooth
   - Check for connection pool exhaustion

### Common Issues and Solutions

#### Issue: High P99 latency (> 2s)

**Possible Causes:**
- Database connection pool exhausted
- Redis cache misses
- JVM garbage collection pauses
- Network latency

**Solutions:**
- Increase database connection pool size
- Warm up Redis cache before test
- Tune JVM GC settings
- Run tests closer to application

#### Issue: High 409 conflict rate (> 50%)

**Expected Behavior:** During stress tests with limited inventory

**Investigation:**
```bash
# Check inventory levels
curl http://localhost:8080/api/v1/products/SKU-LOAD-001/availability

# Monitor reservation conflicts in logs
grep "Conflict" logs/application.log | wc -l
```

#### Issue: 500 errors during load

**Critical Issue:** Indicates server-side failures

**Investigation Steps:**
1. Check application logs for exceptions
2. Monitor database connection pool
3. Check Redis connectivity
4. Verify Kafka producer health
5. Monitor JVM memory usage

## Monitoring During Load Tests

### Application Metrics

```bash
# Monitor application health
watch -n 5 'curl -s http://localhost:8080/actuator/health | jq'

# Check metrics
curl http://localhost:8080/actuator/metrics/http.server.requests

# Monitor database connections
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
```

### Database Monitoring

```sql
-- Check active connections
SELECT count(*) FROM pg_stat_activity WHERE state = 'active';

-- Monitor slow queries
SELECT pid, now() - query_start as duration, query
FROM pg_stat_activity
WHERE state = 'active'
ORDER BY duration DESC;

-- Check locks
SELECT * FROM pg_locks WHERE NOT granted;
```

### Redis Monitoring

```bash
# Connect to Redis CLI
redis-cli

# Monitor commands
> MONITOR

# Check memory usage
> INFO memory

# Check connected clients
> CLIENT LIST
```

## Best Practices

### 1. Test Environment

- Use dedicated test environment
- Match production specs as closely as possible
- Isolate from development/staging traffic
- Clean up test data between runs

### 2. Progressive Load Testing

Always follow this sequence:

1. **Smoke Test** (10 users) - Validate basic functionality
2. **Load Test** (50-100 users) - Normal expected load
3. **Stress Test** (200-500 users) - Beyond expected load
4. **Soak Test** (Sustained load) - Memory leak detection

### 3. Realistic Scenarios

- Model actual user behavior with think times
- Include failed requests in scenarios
- Test retry logic
- Simulate network delays

### 4. Test Data Management

- Pre-create sufficient test inventory
- Use unique user IDs per test run
- Clean up reservations after tests
- Monitor database size growth

## CI/CD Integration

### Jenkins Pipeline Example

```groovy
stage('Load Test') {
    steps {
        sh '''
            mvn gatling:test -DscenarioName=smoke
        '''
    }
    post {
        always {
            gatlingArchive()
        }
    }
}
```

### GitHub Actions Example

```yaml
- name: Run Load Tests
  run: |
    mvn gatling:test -DscenarioName=smoke

- name: Upload Gatling Results
  uses: actions/upload-artifact@v3
  with:
    name: gatling-results
    path: target/gatling
```

## Performance Baseline

Record baseline metrics for comparison:

```bash
# Run baseline test and save results
mvn gatling:test -DscenarioName=mixed > baseline-results.txt

# Compare with new results after changes
mvn gatling:test -DscenarioName=mixed > new-results.txt
diff baseline-results.txt new-results.txt
```

## Troubleshooting

### Gatling Not Starting

```bash
# Check Java version
java -version

# Verify Maven
mvn -version

# Clean and rebuild
mvn clean install
```

### Application Not Responding

```bash
# Check if app is running
curl http://localhost:8080/actuator/health

# Restart application
./mvnw spring-boot:run
```

### Out of Memory Errors

```bash
# Increase Maven memory
export MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=512m"

# Or in pom.xml Gatling plugin configuration:
<jvmArgs>
    <jvmArg>-Xmx2048m</jvmArg>
</jvmArgs>
```

## Additional Resources

- [Gatling Documentation](https://gatling.io/docs/)
- [Gatling Best Practices](https://gatling.io/docs/gatling/guides/best_practices/)
- [Performance Testing Guide](https://martinfowler.com/articles/performance-testing.html)

## Support

For issues or questions:
- Review application logs: `logs/application.log`
- Check Gatling logs: `target/gatling/*.log`
- Open issue in project repository
