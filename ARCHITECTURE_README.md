# Flash Sale System - Architecture Documentation

This directory contains comprehensive architecture documentation for the Flash Sale system, designed to handle high-traffic e-commerce flash sales with zero-oversell guarantees.

## Documentation Overview

### Core Architecture Documents

1. **[SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)**
   - Initial system architecture and design decisions
   - High-level overview of the flash sale system
   - Core components and data flow

2. **[SYSTEM_ARCHITECTURE_ULTRA.md](SYSTEM_ARCHITECTURE_ULTRA.md)**
   - Enhanced architecture with advanced optimizations
   - Detailed performance analysis and capacity planning
   - Failure mode analysis and mitigation strategies

3. **[SYSTEM_ARCHITECTURE_ULTRA_V2.md](SYSTEM_ARCHITECTURE_ULTRA_V2.md)** ⭐ **RECOMMENDED**
   - **Latest and most comprehensive architecture document**
   - Multi-product flash sale support (1-100 products per event)
   - Complete implementation of all architectural decisions:
     - Decision 1: Reservation-based checkout flow
     - Decision 2: Single-writer pattern via Kafka partitioning
     - Decision 3: Cache-first read strategy
     - Decision 4: Per-user purchase limits
     - Decision 5: Rate limiting & fair queuing (Token Bucket + Tiered)
     - Decision 6: Reservation expiry & stock release (Three-Layer Redundancy)
     - Decision 7: Product search functionality
   - 250k RPS read capacity, 25k RPS write capacity
   - India-focused deployment (Mumbai region)

### Supplementary Documentation

4. **[ARCHITECTURE_FLOWCHARTS.md](ARCHITECTURE_FLOWCHARTS.md)**
   - Visual flowcharts for all major system flows
   - Sequence diagrams for API interactions
   - Data flow diagrams
   - Includes:
     - Reservation creation flow
     - Order checkout flow
     - Inventory batch processing
     - Cache synchronization
     - Error handling flows

5. **[FLASH_SALE_SOLUTION_DESIGN.md](FLASH_SALE_SOLUTION_DESIGN.md)**
   - Solution design document with implementation details
   - API specifications and request/response formats
   - Database schema and indexing strategy
   - Monitoring and observability setup

## Quick Start Guide

**New to the project?** Read in this order:

1. Start with **[SYSTEM_ARCHITECTURE_ULTRA_V2.md](SYSTEM_ARCHITECTURE_ULTRA_V2.md)** (latest version)
   - Section 1: System Overview & Requirements
   - Section 2: Architectural Decisions (skim all 7 decisions)
   - Section 3: Failure Modes & Monitoring

2. Review **[ARCHITECTURE_FLOWCHARTS.md](ARCHITECTURE_FLOWCHARTS.md)** for visual understanding
   - Reservation Flow
   - Checkout Flow
   - Batch Processing Flow

3. Check **[FLASH_SALE_SOLUTION_DESIGN.md](FLASH_SALE_SOLUTION_DESIGN.md)** for implementation details
   - API contracts
   - Database schema
   - Deployment configuration

## Key System Capabilities

Based on SYSTEM_ARCHITECTURE_ULTRA_V2:

- **Performance**: 250k RPS reads, 25k RPS writes
- **Scale**: 1-100 products per flash sale event
- **Reliability**: Zero-oversell guarantee via atomic batch processing
- **Latency**: <50ms P95 for reservation creation
- **Availability**: 99.9% uptime with multi-layer redundancy
- **Security**: Rate limiting, bot detection, user tier assignment

## Architecture Highlights

### Three-Layer Redundancy System
- **Layer 1**: Redis TTL (auto-expire in 120s)
- **Layer 2**: Scheduled cleanup job (every 10s)
- **Layer 3**: Kafka event stream (reactive)

### Single-Writer Pattern
- Kafka topic partitioned by SKU
- One consumer per partition
- Batch processing (250 requests/batch)
- Eliminates race conditions

### Cache-First Strategy
- Redis cache for hot data (stock counts, reservations)
- PostgreSQL as source of truth
- Write-through caching for consistency

## Technology Stack

- **API**: Spring Boot REST
- **Database**: PostgreSQL (RDS)
- **Cache**: Redis (ElastiCache)
- **Message Queue**: Kafka (MSK)
- **Metrics**: CloudWatch
- **Deployment**: AWS (Mumbai region)

## Related Documentation

- **[README.md](README.md)** - Project setup and running instructions
- **[AWS_SETUP.md](AWS_SETUP.md)** - AWS infrastructure setup guide
- **[LOAD_TESTING.md](LOAD_TESTING.md)** - Load testing procedures and results

## Document Versions

| Document | Version | Last Updated | Status |
|----------|---------|--------------|--------|
| SYSTEM_ARCHITECTURE.md | v1.0 | Initial | Archived |
| SYSTEM_ARCHITECTURE_ULTRA.md | v2.0 | Enhanced | Superseded |
| **SYSTEM_ARCHITECTURE_ULTRA_V2.md** | **v3.0** | **Latest** | **Active** |
| ARCHITECTURE_FLOWCHARTS.md | v1.0 | Current | Active |
| FLASH_SALE_SOLUTION_DESIGN.md | v1.0 | Current | Active |

---

**For questions or clarifications, refer to the inline comments and decision rationale in [SYSTEM_ARCHITECTURE_ULTRA_V2.md](SYSTEM_ARCHITECTURE_ULTRA_V2.md).**
