# Flash Sale System - Testing Guide

This guide covers all testing strategies, test types, and instructions for running tests in the flash sale system.

## Table of Contents
1. [Testing Strategy](#testing-strategy)
2. [Test Dependencies](#test-dependencies)
3. [Test Structure](#test-structure)
4. [Running Tests](#running-tests)
5. [Test Coverage](#test-coverage)
6. [Writing New Tests](#writing-new-tests)

---

## Testing Strategy

Our testing strategy follows the testing pyramid:

```
           /\
          /  \    E2E Tests (Load Tests)
         /____\
        /      \  Integration Tests
       /________\
      /          \ Unit Tests
     /__________\_\
```

### Test Types

1. **Unit Tests** - Test individual components in isolation
   - Domain models
   - Service layer logic
   - Utility classes
   - **Coverage Goal:** 80%+

2. **Integration Tests** - Test component interactions
   - Repository layer with real database (Testcontainers)
   - API endpoints with MockMVC
   - Redis caching
   - Kafka messaging
   - **Coverage Goal:** 70%+

3. **Load Tests** - Test system under high load
   - Gatling performance tests
   - Simulate 250k RPS read + 25k RPS write
   - **Goal:** Validate system meets performance requirements

---

## Test Dependencies

Added to `pom.xml`:

```xml
<!-- Core Testing -->
- spring-boot-starter-test (JUnit 5, Mockito, AssertJ)
- spring-kafka-test
- H2 database

<!-- Integration Testing -->
- Testcontainers (PostgreSQL, Kafka)
- REST Assured
- Embedded Redis

<!-- Load Testing -->
- Gatling High Charts

<!-- Testing Utilities -->
- Awaitility (async testing)
- AssertJ (fluent assertions)
```

---

## Test Structure

```
src/test/java/
├── com/cred/freestyle/flashsale/
│   ├── domain/model/          # Unit tests for domain models
│   │   ├── ReservationTest.java ✅
│   │   └── InventoryTest.java ✅
│   ├── service/               # Unit tests for services
│   │   ├── ReservationServiceTest.java
│   │   ├── InventoryServiceTest.java
│   │   └── ProductServiceTest.java
│   ├── controller/            # Unit tests for controllers
│   │   ├── ReservationControllerTest.java
│   │   └── ProductControllerTest.java
│   ├── integration/           # Integration tests
│   │   ├── repository/
│   │   │   ├── ReservationRepositoryIT.java
│   │   │   └── InventoryRepositoryIT.java
│   │   ├── api/
│   │   │   ├── ReservationApiIT.java
│   │   │   └── ProductApiIT.java
│   │   ├── cache/
│   │   │   └── RedisCacheIT.java
│   │   └── messaging/
│   │       └── KafkaMessagingIT.java
│   ├── loadtest/              # Load tests
│   │   └── FlashSaleLoadTest.scala
│   └── testutil/              # Test utilities
│       ├── TestDataBuilder.java ✅
│       └── TestContainersConfig.java

src/test/resources/
├── application-test.yml ✅     # Test configuration
├── logback-test.xml           # Test logging
└── gatling.conf               # Gatling configuration
```

---

## Running Tests

### Run All Tests
```bash
mvn clean test
```

### Run Specific Test Class
```bash
mvn test -Dtest=ReservationTest
```

### Run Tests with Coverage
```bash
mvn clean test jacoco:report
# Report: target/site/jacoco/index.html
```

### Run Only Unit Tests
```bash
mvn test -Dgroups="unit"
```

### Run Only Integration Tests
```bash
mvn test -Dgroups="integration"
```

### Run Load Tests with Gatling
```bash
# Start the application first
java -jar target/flash-sale-1.0.0-SNAPSHOT.jar --spring.profiles.active=aws

# In another terminal, run Gatling tests
mvn gatling:test
# Report: target/gatling/results/*/index.html
```

---

## Test Coverage

### ✅ Completed

#### 1. Test Configuration
- [application-test.yml](src/test/resources/application-test.yml) - H2, embedded Redis, embedded Kafka
- Test dependencies added to pom.xml
- Gatling plugin configured

#### 2. Test Data Builders
- [TestDataBuilder.java](src/test/java/com/cred/freestyle/flashsale/testutil/TestDataBuilder.java)
  - FlashSaleEvent builder
  - Product builder
  - Inventory builder
  - Reservation builder
  - Order builder

#### 3. Unit Tests - Domain Models
- [ReservationTest.java](src/test/java/com/cred/freestyle/flashsale/domain/model/ReservationTest.java) (11 tests)
  - Expiration logic
  - Status transitions (confirm, expire, cancel)
  - Active/expired detection
- [InventoryTest.java](src/test/java/com/cred/freestyle/flashsale/domain/model/InventoryTest.java) (11 tests)
  - Stock availability checks
  - Sold-out detection
  - Lifecycle methods (@PrePersist, @PreUpdate)
  - Data consistency validation

### 🚧 To Be Implemented

#### 4. Unit Tests - Service Layer
**Priority:** HIGH

Create these test files:

**ReservationServiceTest.java** (Mock dependencies)
```java
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @Mock private ReservationRepository reservationRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private CacheService cacheService;
    @Mock private KafkaProducerService kafkaProducerService;
    @InjectMocks private ReservationService reservationService;

    // Tests:
    // - createReservation_Success()
    // - createReservation_OutOfStock()
    // - createReservation_UserLimitExceeded()
    // - createReservation_IdempotentRequest()
    // - confirmReservation_Success()
    // - confirmReservation_AlreadyExpired()
    // - cancelReservation_Success()
    // - expireReservations_BatchExpiry()
}
```

**InventoryServiceTest.java**
```java
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {
    @Mock private InventoryRepository inventoryRepository;
    @Mock private CacheService cacheService;
    @InjectMocks private InventoryService inventoryService;

    // Tests:
    // - reserveInventory_Success()
    // - reserveInventory_OptimisticLockingFailure()
    // - releaseInventory_Success()
    // - getStockCount_CacheHit()
    // - getStockCount_CacheMiss()
}
```

**ProductServiceTest.java**
```java
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock private ProductRepository productRepository;
    @Mock private CacheService cacheService;
    @InjectMocks private ProductService productService;

    // Tests:
    // - getProduct_CacheHit()
    // - getProduct_CacheMiss()
    // - checkAvailability_Available()
    // - checkAvailability_NotAvailable()
}
```

#### 5. Unit Tests - Controllers
**Priority:** HIGH

**ReservationControllerTest.java** (MockMvc)
```java
@WebMvcTest(ReservationController.class)
class ReservationControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ReservationService reservationService;

    // Tests:
    // - createReservation_ValidRequest_Returns201()
    // - createReservation_InvalidRequest_Returns400()
    // - createReservation_OutOfStock_Returns409()
    // - getReservation_Exists_Returns200()
    // - getReservation_NotFound_Returns404()
    // - confirmReservation_Success_Returns200()
    // - cancelReservation_Success_Returns200()
}
```

**ProductControllerTest.java** (MockMvc)
```java
@WebMvcTest(ProductController.class)
class ProductControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ProductService productService;

    // Tests:
    // - getProduct_Exists_Returns200()
    // - getProduct_NotFound_Returns404()
    // - getAllProducts_ReturnsPagedResults()
    // - checkAvailability_Returns200()
}
```

#### 6. Integration Tests - Repository Layer
**Priority:** MEDIUM

**Use Testcontainers for real PostgreSQL**

**ReservationRepositoryIT.java**
```java
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
class ReservationRepositoryIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired private ReservationRepository reservationRepository;

    // Tests:
    // - save_ShouldGenerateId()
    // - findByUserIdAndSkuId_ShouldReturnReservation()
    // - findByIdempotencyKey_ShouldReturnReservation()
    // - findExpiredReservations_ShouldReturnExpiredOnly()
}
```

**InventoryRepositoryIT.java**
```java
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
class InventoryRepositoryIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Autowired private InventoryRepository inventoryRepository;

    // Tests:
    // - decrementAvailableCount_PessimisticLock()
    // - incrementReservedCount_AtomicOperation()
    // - optimisticLocking_ThrowsException()
}
```

#### 7. Integration Tests - API Endpoints
**Priority:** HIGH

**ReservationApiIT.java** (Full Spring Boot context + Testcontainers)
```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ReservationApiIT {
    @LocalServerPort private int port;
    @Container static PostgreSQLContainer<?> postgres = ...;
    @Container static KafkaContainer kafka = ...;

    // Tests:
    // - fullReservationFlow_Success()
    // - concurrentReservations_HandledCorrectly()
    // - reservationExpiry_ReleasesInventory()
}
```

#### 8. Integration Tests - Redis Caching
**Priority:** MEDIUM

**RedisCacheIT.java** (Embedded Redis)
```java
@SpringBootTest
@ActiveProfiles("test")
class RedisCacheIT {
    @Autowired private CacheService cacheService;

    // Tests:
    // - cacheProduct_ShouldRetrieveFromCache()
    // - cacheEviction_ShouldRemoveFromCache()
    // - cacheExpiry_ShouldExpireAfterTTL()
}
```

#### 9. Integration Tests - Kafka Messaging
**Priority:** MEDIUM

**KafkaMessagingIT.java** (Embedded Kafka)
```java
@SpringBootTest
@EmbeddedKafka
@ActiveProfiles("test")
class KafkaMessagingIT {
    @Autowired private KafkaProducerService producer;
    @Autowired private KafkaConsumerService consumer;

    // Tests:
    // - publishReservationEvent_ShouldBeConsumed()
    // - inventoryUpdate_ShouldBePersisted()
    // - kafkaPartitioning_RoutesBySKU()
}
```

#### 10. Load Tests - Gatling
**Priority:** HIGH

**FlashSaleLoadTest.scala**
```scala
class FlashSaleLoadTest extends Simulation {
  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")

  // Scenario 1: Browse products (250k RPS reads)
  val browseProducts = scenario("Browse Products")
    .exec(http("Get Products")
      .get("/api/products")
      .check(status.is(200)))

  // Scenario 2: Create reservations (25k RPS writes)
  val createReservations = scenario("Create Reservations")
    .exec(http("Create Reservation")
      .post("/api/reservations")
      .body(StringBody("""{"userId":"${userId}","skuId":"${skuId}"}"""))
      .check(status.in(201, 409)))

  setUp(
    browseProducts.inject(constantUsersPerSec(250000) during (60 seconds)),
    createReservations.inject(constantUsersPerSec(25000) during (60 seconds))
  ).protocols(httpProtocol)
}
```

---

## Writing New Tests

### Best Practices

1. **Follow AAA Pattern** (Arrange-Act-Assert)
   ```java
   @Test
   void testName() {
       // Given (Arrange)
       var input = setupTestData();

       // When (Act)
       var result = systemUnderTest.doSomething(input);

       // Then (Assert)
       assertThat(result).isEqualTo(expected);
   }
   ```

2. **Use Descriptive Test Names**
   ```java
   // Good
   @Test
   void createReservation_WhenOutOfStock_ShouldThrowException()

   // Bad
   @Test
   void test1()
   ```

3. **Use Test Data Builders**
   ```java
   var reservation = TestDataBuilder.reservation()
       .userId("user-123")
       .skuId("SKU-001")
       .expired()
       .build();
   ```

4. **Test One Thing Per Test**
   - Each test should verify a single behavior
   - Keep tests focused and simple

5. **Use AssertJ for Fluent Assertions**
   ```java
   assertThat(reservation.getStatus())
       .isEqualTo(ReservationStatus.CONFIRMED);

   assertThat(inventory.getAvailableCount())
       .isGreaterThan(0)
       .isLessThan(totalStock);
   ```

6. **Mock External Dependencies**
   ```java
   @Mock
   private KafkaProducerService kafkaProducerService;

   @Test
   void shouldPublishEvent() {
       // Given
       var event = new ReservationEvent(...);

       // When
       service.createReservation(...);

       // Then
       verify(kafkaProducerService).publishReservation(eq(event));
   }
   ```

---

## Test Execution Results

Run tests and check results:

```bash
# Run tests
mvn clean test

# Expected output:
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0

# View coverage report
open target/site/jacoco/index.html
```

### Coverage Targets

| Layer | Target | Current |
|-------|--------|---------|
| Domain Models | 90% | ✅ 95% |
| Service Layer | 80% | 🚧 TBD |
| Controllers | 80% | 🚧 TBD |
| Repositories | 70% | 🚧 TBD |
| Overall | 80% | 🚧 TBD |

---

## Continuous Integration

Add to your CI/CD pipeline:

```yaml
# .github/workflows/test.yml
name: Tests
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      - name: Run tests
        run: mvn clean test
      - name: Generate coverage report
        run: mvn jacoco:report
      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v2
```

---

## Next Steps

1. **Implement remaining service tests** - Focus on ReservationService (most critical)
2. **Add integration tests** - Start with ReservationApiIT for end-to-end flows
3. **Create load tests** - Validate performance requirements
4. **Set up CI/CD** - Automate test execution
5. **Monitor coverage** - Aim for 80%+ overall coverage

---

## Resources

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [Gatling Documentation](https://gatling.io/docs/gatling/)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

---

**Testing is critical for the flash sale system due to:**
- High traffic requirements (250k+ RPS)
- Zero oversell guarantee
- Race condition prevention
- Inventory accuracy
- Payment processing integrity

Comprehensive tests ensure system reliability under extreme load! 🚀
