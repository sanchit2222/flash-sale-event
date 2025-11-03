package com.cred.freestyle.flashsale.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Gatling load test for Flash Sale application.
 * Simulates realistic user behavior during a flash sale event.
 *
 * Test Scenarios:
 * 1. Product Browsing - Users viewing available products
 * 2. Flash Sale Rush - High concurrent load on reservation endpoint
 * 3. Complete Purchase Flow - End-to-end reservation to checkout
 * 4. Mixed Workload - Combination of all operations
 *
 * Run with: mvn gatling:test
 *
 * @author Flash Sale Team
 */
class FlashSaleLoadTest extends Simulation {

  // ========================================
  // Configuration
  // ========================================

  val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
  val testDuration = Integer.getInteger("testDuration", 60).seconds
  val rampUsers = Integer.getInteger("rampUsers", 100)
  val sustainedUsers = Integer.getInteger("sustainedUsers", 50)

  // Test SKUs
  val skuIds = List("SKU-001", "SKU-002", "SKU-003", "SKU-004", "SKU-005")
  val skuFeeder = skuIds.map(sku => Map("skuId" -> sku)).circular

  // User ID generator
  val userIdFeeder = Iterator.from(1).map(i => Map("userId" -> s"user-load-$i"))

  // ========================================
  // HTTP Protocol Configuration
  // ========================================

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling Load Test")

  // ========================================
  // Scenario 1: Product Browsing
  // ========================================

  val productBrowsing = scenario("Product Browsing")
    .feed(skuFeeder)
    .exec(
      http("Get Product Availability")
        .get("/api/v1/products/${skuId}/availability")
        .check(status.is(200))
        .check(jsonPath("$.skuId").exists)
        .check(jsonPath("$.availableCount").exists)
    )
    .pause(1, 3)
    .exec(
      http("List All Products")
        .get("/api/v1/products")
        .check(status.is(200))
        .check(jsonPath("$[*]").exists)
    )

  // ========================================
  // Scenario 2: Flash Sale Rush (High Load)
  // ========================================

  val flashSaleRush = scenario("Flash Sale Rush")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    .exec(
      http("Create Reservation")
        .post("/api/v1/reservations")
        .body(StringBody(
          """{
            |  "userId": "${userId}",
            |  "skuId": "${skuId}",
            |  "quantity": 1
            |}""".stripMargin))
        .check(status.in(201, 400, 409, 500))
        .check(
          status.saveAs("reservationStatus")
        )
        .check(
          checkIf(status.is(201)) {
            jsonPath("$.reservationId").saveAs("reservationId")
          }
        )
    )
    .doIf(session => session("reservationStatus").as[Int] == 201) {
      exec(
        http("Get Reservation Details")
          .get("/api/v1/reservations/${reservationId}")
          .check(status.is(200))
      )
    }

  // ========================================
  // Scenario 3: Complete Purchase Flow
  // ========================================

  val completePurchaseFlow = scenario("Complete Purchase Flow")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    // Step 1: Browse product
    .exec(
      http("View Product")
        .get("/api/v1/products/${skuId}/availability")
        .check(status.is(200))
        .check(jsonPath("$.availableCount").ofType[Int].gt(0).saveAs("available"))
    )
    .pause(1, 2)
    // Step 2: Create reservation
    .doIf(session => session.contains("available") && session("available").as[Int] > 0) {
      exec(
        http("Create Reservation")
          .post("/api/v1/reservations")
          .body(StringBody(
            """{
              |  "userId": "${userId}",
              |  "skuId": "${skuId}",
              |  "quantity": 1
              |}""".stripMargin))
          .check(status.in(201, 400, 409))
          .check(
            checkIf(status.is(201)) {
              jsonPath("$.reservationId").saveAs("reservationId")
            }
          )
          .check(status.saveAs("reservationStatus"))
      )
        .pause(2, 5)
        // Step 3: Complete checkout if reservation succeeded
        .doIf(session => session("reservationStatus").as[Int] == 201) {
          exec(
            http("Checkout Order")
              .post("/api/v1/orders/checkout")
              .body(StringBody(
                """{
                  |  "reservationId": "${reservationId}",
                  |  "paymentMethod": "CREDIT_CARD",
                  |  "paymentTransactionId": "TXN-${userId}-${skuId}",
                  |  "shippingAddress": "123 Test Street, Load Test City"
                  |}""".stripMargin))
              .check(status.in(201, 400, 404))
              .check(
                checkIf(status.is(201)) {
                  jsonPath("$.orderId").saveAs("orderId")
                }
              )
          )
            .pause(1)
            // Step 4: Verify order
            .doIf(session => session.contains("orderId")) {
              exec(
                http("Get Order Details")
                  .get("/api/v1/orders/${orderId}")
                  .check(status.is(200))
                  .check(jsonPath("$.status").is("CONFIRMED"))
              )
            }
        }
    }

  // ========================================
  // Scenario 4: Mixed Workload
  // ========================================

  val mixedWorkload = scenario("Mixed Workload")
    .feed(userIdFeeder)
    .feed(skuFeeder)
    .randomSwitch(
      40.0 -> exec(
        http("Browse Products")
          .get("/api/v1/products")
          .check(status.is(200))
      ),
      30.0 -> exec(
        http("Check Product Availability")
          .get("/api/v1/products/${skuId}/availability")
          .check(status.is(200))
      ),
      20.0 -> exec(
        http("Attempt Reservation")
          .post("/api/v1/reservations")
          .body(StringBody(
            """{
              |  "userId": "${userId}",
              |  "skuId": "${skuId}",
              |  "quantity": 1
              |}""".stripMargin))
          .check(status.in(201, 400, 409, 500))
      ),
      10.0 -> exec(
        http("Get User Reservations")
          .get("/api/v1/reservations/user/${userId}/active")
          .check(status.is(200))
      )
    )
    .pause(500.milliseconds, 2.seconds)

  // ========================================
  // Scenario 5: Stress Test - Inventory Contention
  // ========================================

  val inventoryStressTest = scenario("Inventory Stress Test")
    .feed(userIdFeeder)
    // All users compete for the same SKU
    .exec(session => session.set("skuId", "SKU-001"))
    .exec(
      http("Compete for Limited Inventory")
        .post("/api/v1/reservations")
        .body(StringBody(
          """{
            |  "userId": "${userId}",
            |  "skuId": "${skuId}",
            |  "quantity": 1
            |}""".stripMargin))
        .check(status.in(201, 400, 409, 500))
        .check(
          checkIf(status.is(201)) {
            jsonPath("$.reservationId").exists
          }
        )
    )

  // ========================================
  // Scenario 6: Read-Heavy Workload
  // ========================================

  val readHeavyWorkload = scenario("Read-Heavy Workload")
    .feed(skuFeeder)
    .exec(
      http("Get Product Details")
        .get("/api/v1/products/${skuId}/availability")
        .check(status.is(200))
    )
    .pause(200.milliseconds)
    .exec(
      http("List Products")
        .get("/api/v1/products")
        .check(status.is(200))
    )
    .pause(200.milliseconds)

  // ========================================
  // Load Test Configurations
  // ========================================

  // Configuration 1: Gradual Ramp-Up (Smoke Test)
  val smokeTest = productBrowsing.inject(
    rampUsers(10) during (10.seconds),
    constantUsersPerSec(5) during (20.seconds)
  )

  // Configuration 2: Flash Sale Event Simulation
  val flashSaleEvent = flashSaleRush.inject(
    nothingFor(5.seconds),
    atOnceUsers(50),              // Initial burst
    rampUsers(200) during (30.seconds), // Ramp up
    constantUsersPerSec(20) during (60.seconds) // Sustained load
  )

  // Configuration 3: Complete Purchase Flow
  val purchaseFlowTest = completePurchaseFlow.inject(
    rampUsers(50) during (30.seconds),
    constantUsersPerSec(10) during (90.seconds)
  )

  // Configuration 4: Mixed Workload Test
  val mixedWorkloadTest = mixedWorkload.inject(
    rampUsers(100) during (30.seconds),
    constantUsersPerSec(30) during (120.seconds)
  )

  // Configuration 5: Stress Test
  val stressTest = inventoryStressTest.inject(
    nothingFor(2.seconds),
    atOnceUsers(200)  // Immediate concurrent spike
  )

  // Configuration 6: Endurance Test (Read-Heavy)
  val enduranceTest = readHeavyWorkload.inject(
    rampUsers(100) during (20.seconds),
    constantUsersPerSec(50) during (300.seconds)
  )

  // ========================================
  // Test Execution
  // ========================================

  // Default: Run mixed workload test
  // To run specific scenarios, use: -DscenarioName=<name>
  val scenarioName = System.getProperty("scenarioName", "mixed")

  val selectedScenario = scenarioName match {
    case "smoke" => setUp(smokeTest.protocols(httpProtocol))
    case "flashsale" => setUp(flashSaleEvent.protocols(httpProtocol))
    case "purchase" => setUp(purchaseFlowTest.protocols(httpProtocol))
    case "mixed" => setUp(mixedWorkloadTest.protocols(httpProtocol))
    case "stress" => setUp(stressTest.protocols(httpProtocol))
    case "endurance" => setUp(enduranceTest.protocols(httpProtocol))
    case "full" => setUp(
      smokeTest.protocols(httpProtocol),
      flashSaleEvent.protocols(httpProtocol),
      purchaseFlowTest.protocols(httpProtocol),
      mixedWorkloadTest.protocols(httpProtocol)
    )
    case _ => setUp(mixedWorkloadTest.protocols(httpProtocol))
  }

  // ========================================
  // Assertions
  // ========================================

  selectedScenario.assertions(
    global.responseTime.max.lt(5000),           // Max response time < 5s
    global.responseTime.percentile3.lt(2000),   // 99th percentile < 2s
    global.successfulRequests.percent.gt(70)    // At least 70% success rate
  )
}
