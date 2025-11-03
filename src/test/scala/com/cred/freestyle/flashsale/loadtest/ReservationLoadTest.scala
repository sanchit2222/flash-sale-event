package com.cred.freestyle.flashsale.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Focused load test for Reservation API endpoint with Kafka batch processing.
 * Tests the most critical path during flash sale events.
 *
 * This test simulates the "thundering herd" problem where thousands
 * of users attempt to reserve limited inventory simultaneously.
 *
 * System Requirements (from FLASH_SALE_SOLUTION_DESIGN.md):
 * - Throughput: 25,000 requests per second (25K RPS)
 * - P95 latency: < 120ms
 * - P99 latency: < 200ms
 * - Zero data corruption (no overselling)
 * - Fair distribution (FIFO within batches)
 *
 * Additional Performance Targets:
 * - P95 latency < 500ms under normal load
 * - P99 latency < 1000ms under normal load
 * - Graceful degradation under extreme load
 *
 * Run specific test scenarios:
 *   mvn gatling:test -Dgatling.simulationClass=com.cred.freestyle.flashsale.loadtest.ReservationLoadTest
 *   mvn gatling:test -DtestType=25k-rps
 *   mvn gatling:test -DtestType=peak
 *   mvn gatling:test -DtestType=stress
 *
 * @author Flash Sale Team
 */
class ReservationLoadTest extends Simulation {

  // ========================================
  // Configuration
  // ========================================

  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")

  // Load test parameters
  val normalLoad = Integer.getInteger("normalLoad", 50)       // Normal concurrent users
  val peakLoad = Integer.getInteger("peakLoad", 200)         // Peak concurrent users
  val extremeLoad = Integer.getInteger("extremeLoad", 500)   // Stress test load

  // Test data
  val skuIds = List("SKU-LOAD-001", "SKU-LOAD-002", "SKU-LOAD-003")
  val skuFeeder = skuIds.map(sku => Map("skuId" -> sku)).circular
  val userIdFeeder = Iterator.from(1).map(i => Map(
    "userId" -> s"user-perf-$i",
    "requestId" -> s"req-${System.currentTimeMillis()}-$i"
  ))

  // ========================================
  // HTTP Protocol Configuration
  // ========================================

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .header("X-Request-ID", "${requestId}")
    .userAgentHeader("Gatling Reservation Load Test")
    // Connection pooling for performance
    .shareConnections

  // ========================================
  // Request Templates
  // ========================================

  val createReservationRequest = http("Create Reservation")
    .post("/api/v1/reservations")
    .body(StringBody(
      """{
        |  "userId": "${userId}",
        |  "skuId": "${skuId}",
        |  "quantity": 1
        |}""".stripMargin))
    .check(status.in(201, 400, 409, 500))
    .check(status.saveAs("statusCode"))
    .check(
      checkIf(status.is(201)) {
        jsonPath("$.reservationId").saveAs("reservationId")
      }
    )
    .check(
      checkIf(status.is(400)) {
        jsonPath("$.message").exists
      }
    )

  val getReservationRequest = http("Get Reservation")
    .get("/api/v1/reservations/${reservationId}")
    .check(status.is(200))
    .check(jsonPath("$.status").exists)

  val cancelReservationRequest = http("Cancel Reservation")
    .delete("/api/v1/reservations/${reservationId}")
    .check(status.in(200, 204))

  // ========================================
  // Scenario: Basic Reservation Flow
  // ========================================

  val basicReservationFlow = scenario("Basic Reservation Flow")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    .exec(createReservationRequest)
    .pause(1)
    .doIf(session => session("statusCode").as[Int] == 201) {
      exec(getReservationRequest)
        .pause(500.milliseconds)
    }

  // ========================================
  // Scenario: Reservation with Cancellation
  // ========================================

  val reservationWithCancellation = scenario("Reservation with Cancellation")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    .exec(createReservationRequest)
    .pause(1, 3)
    .doIf(session => session("statusCode").as[Int] == 201) {
      exec(cancelReservationRequest)
    }

  // ========================================
  // Scenario: Concurrent Reservation Attempts
  // ========================================

  val concurrentReservations = scenario("Concurrent Reservations")
    .feed(userIdFeeder)
    // All users compete for same SKU
    .exec(session => session.set("skuId", "SKU-LOAD-001"))
    .exec(createReservationRequest)
    .doIf(session => session("statusCode").as[Int] == 201) {
      exec(
        http("Verify Reservation Success")
          .get("/api/v1/reservations/${reservationId}")
          .check(status.is(200))
          .check(jsonPath("$.status").is("RESERVED"))
      )
    }
    .doIf(session => session("statusCode").as[Int] == 409) {
      exec(session => {
        println(s"User ${session("userId").as[String]} - Conflict: Product already reserved or out of stock")
        session
      })
    }

  // ========================================
  // Scenario: Duplicate Request Prevention
  // ========================================

  val duplicateRequestTest = scenario("Duplicate Request Prevention")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    // First request
    .exec(createReservationRequest)
    .pause(100.milliseconds)
    // Immediate duplicate request (same user, same SKU)
    .exec(
      http("Duplicate Reservation Attempt")
        .post("/api/v1/reservations")
        .body(StringBody(
          """{
            |  "userId": "${userId}",
            |  "skuId": "${skuId}",
            |  "quantity": 1
            |}""".stripMargin))
        .check(status.in(409, 400))  // Should be rejected
    )

  // ========================================
  // Scenario: High Throughput Test
  // ========================================

  val highThroughputTest = scenario("High Throughput Test")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    .exec(createReservationRequest)
    .pause(10.milliseconds)  // Minimal pause for maximum throughput

  // ========================================
  // Scenario: Retry Logic Test
  // ========================================

  val retryLogicTest = scenario("Retry Logic Test")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    .exec(createReservationRequest)
    .pause(100.milliseconds)
    // Retry if failed (simulate client retry behavior)
    .doIf(session => session("statusCode").as[Int] >= 500) {
      exec(
        http("Retry Reservation")
          .post("/api/v1/reservations")
          .body(StringBody(
            """{
              |  "userId": "${userId}",
              |  "skuId": "${skuId}",
              |  "quantity": 1
              |}""".stripMargin))
          .check(status.in(201, 400, 409, 500))
      )
    }

  // ========================================
  // Test Execution Plans
  // ========================================

  // Test 1: Baseline Performance Test
  val baselineTest = basicReservationFlow.inject(
    rampUsers(normalLoad) during (30.seconds),
    constantUsersPerSec(10) during (60.seconds)
  )

  // Test 2: Peak Load Test (Flash Sale Start)
  val peakLoadTest = concurrentReservations.inject(
    nothingFor(5.seconds),
    atOnceUsers(100),  // Initial spike
    rampUsers(peakLoad) during (15.seconds),
    constantUsersPerSec(30) during (60.seconds)
  )

  // Test 3: Stress Test (Beyond Capacity)
  val stressTest = concurrentReservations.inject(
    nothingFor(5.seconds),
    atOnceUsers(extremeLoad)  // Extreme concurrent load
  )

  // Test 4: Soak Test (Sustained Load)
  val soakTest = basicReservationFlow.inject(
    rampUsers(normalLoad) during (30.seconds),
    constantUsersPerSec(20) during (600.seconds)  // 10 minutes sustained
  )

  // Test 5: Spike Test
  val spikeTest = basicReservationFlow.inject(
    rampUsers(50) during (30.seconds),
    constantUsersPerSec(10) during (60.seconds),
    atOnceUsers(500),  // Sudden spike
    constantUsersPerSec(10) during (60.seconds)
  )

  // Test 6: Capacity Test
  val capacityTest = highThroughputTest.inject(
    constantUsersPerSec(10) during (30.seconds),
    constantUsersPerSec(20) during (30.seconds),
    constantUsersPerSec(50) during (30.seconds),
    constantUsersPerSec(100) during (30.seconds),
    constantUsersPerSec(200) during (30.seconds)
  )

  // Test 8: 25K RPS Requirement Validation
  // Validates system meets the design requirement of 25K RPS with P95 < 120ms
  val twentyFiveKRpsTest = basicReservationFlow.inject(
    // Ramp up to 25K RPS over 2 minutes
    rampUsersPerSec(0).to(5000) during (30.seconds),
    rampUsersPerSec(5000).to(15000) during (45.seconds),
    rampUsersPerSec(15000).to(25000) during (45.seconds),
    // Sustain 25K RPS for 30 minutes (flash sale duration)
    constantUsersPerSec(25000) during (30.minutes)
  )

  // Test 7: Combined Scenarios
  val combinedTest = setUp(
    basicReservationFlow.inject(
      rampUsers(50) during (20.seconds),
      constantUsersPerSec(10) during (120.seconds)
    ),
    reservationWithCancellation.inject(
      nothingFor(30.seconds),
      rampUsers(30) during (20.seconds),
      constantUsersPerSec(5) during (90.seconds)
    ),
    duplicateRequestTest.inject(
      nothingFor(60.seconds),
      rampUsers(20) during (20.seconds)
    )
  ).protocols(httpProtocol)

  // ========================================
  // Test Selection
  // ========================================

  val testType = System.getProperty("testType", "baseline")

  val selectedTest = testType match {
    case "baseline" => setUp(baselineTest.protocols(httpProtocol))
    case "peak" => setUp(peakLoadTest.protocols(httpProtocol))
    case "stress" => setUp(stressTest.protocols(httpProtocol))
    case "soak" => setUp(soakTest.protocols(httpProtocol))
    case "spike" => setUp(spikeTest.protocols(httpProtocol))
    case "capacity" => setUp(capacityTest.protocols(httpProtocol))
    case "25k-rps" => setUp(twentyFiveKRpsTest.protocols(httpProtocol))
    case "combined" => combinedTest
    case _ => setUp(baselineTest.protocols(httpProtocol))
  }

  // ========================================
  // Assertions
  // ========================================

  // Different assertions for different test types
  val assertions = testType match {
    case "25k-rps" =>
      // Strict assertions for 25K RPS requirement validation
      selectedTest.assertions(
        // Throughput assertions
        global.requestsPerSec.gte(23750),              // Achieve at least 95% of 25K RPS
        global.successfulRequests.percent.gte(90),     // At least 90% success rate

        // Latency assertions (CRITICAL REQUIREMENTS)
        global.responseTime.percentile3.lte(120),      // P95 < 120ms ✓ REQUIRED
        global.responseTime.percentile4.lte(200),      // P99 < 200ms ✓ REQUIRED
        global.responseTime.mean.lte(80),              // Mean < 80ms
        global.responseTime.max.lte(500),              // Max < 500ms

        // Reliability assertions
        global.failedRequests.percent.lte(10)          // Less than 10% failures
      )

    case "stress" =>
      // Relaxed assertions for stress testing (finding breaking point)
      selectedTest.assertions(
        global.responseTime.max.lt(30000),
        global.failedRequests.percent.lt(50)
      )

    case _ =>
      // Standard assertions for other test types
      selectedTest.assertions(
        // Global assertions
        global.responseTime.max.lt(10000),
        global.responseTime.percentile3.lt(2000),    // P99 < 2s
        global.responseTime.percentile4.lt(5000),    // P99.9 < 5s
        global.failedRequests.percent.lt(30),        // Less than 30% failures

        // Per-scenario assertions
        forAll.responseTime.mean.lt(1000),           // Average < 1s
        forAll.successfulRequests.count.gte(10)      // At least 10 successful requests
      )
  }
}
