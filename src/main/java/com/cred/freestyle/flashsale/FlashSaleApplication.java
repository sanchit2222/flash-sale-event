package com.cred.freestyle.flashsale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Main Spring Boot application class for Flash Sale system.
 *
 * System Overview:
 * - Handles high-traffic flash sales (250k RPS read, 25k RPS write)
 * - Multi-product flash sales (1-100 products per event)
 * - Per-product inventory management with zero oversell guarantee
 * - 2-minute reservation holds with automatic expiry
 * - Per-user purchase limits (1 unit per product)
 * - Real-time inventory tracking with Redis caching
 * - Event-driven architecture with Kafka
 * - CloudWatch metrics for observability
 *
 * Architecture:
 * - API Layer: REST controllers with validation
 * - Service Layer: Business logic for reservations, orders, products
 * - Data Access Layer: JPA repositories with optimistic/pessimistic locking
 * - Infrastructure Layer: Redis cache, Kafka messaging, CloudWatch metrics
 *
 * Key Features:
 * - Reservation-based checkout (reserve → pay → confirm)
 * - Cache-first strategy for high-read performance
 * - Single-writer pattern via Kafka SKU-based partitioning
 * - Atomic database operations with pessimistic locking
 * - Comprehensive error handling and observability
 *
 * @author Flash Sale Team
 */
@SpringBootApplication
@EnableJpaRepositories
@EnableTransactionManagement
@EnableKafka
@EnableScheduling
public class FlashSaleApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSaleApplication.class, args);
    }
}
