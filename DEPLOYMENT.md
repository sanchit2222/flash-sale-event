# Flash Sale System - Deployment Guide

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Local Development Deployment](#local-development-deployment)
3. [AWS Production Deployment](#aws-production-deployment)
4. [Configuration](#configuration)
5. [Monitoring and Troubleshooting](#monitoring-and-troubleshooting)

---

## Prerequisites

### Required Software
- **Java 17** or higher
- **Maven 3.9+**
- **Docker** and **Docker Compose** (for local deployment)
- **AWS CLI** (for AWS deployment)
- **Git**

### AWS Requirements (for Production)
- AWS Account with appropriate permissions
- AWS CLI configured with credentials
- Access to AWS services: ECS, RDS, ElastiCache, MSK, CloudWatch

---

## Local Development Deployment

### Option 1: Docker Compose (Recommended)

This is the easiest way to run the entire stack locally with all dependencies.

#### Step 1: Set Environment Variables

Create a `.env` file in the project root:

```bash
# Database
DB_PASSWORD=flashsale_pass_local

# Redis
REDIS_PASSWORD=redis_pass_local

# AWS (for local testing, use dummy values)
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
```

#### Step 2: Build and Run

```bash
# Build the application JAR
mvn clean package -DskipTests

# Start all services
docker-compose up -d

# View logs
docker-compose logs -f app

# Check service health
docker-compose ps
```

#### Step 3: Verify Deployment

```bash
# Health check
curl http://localhost:8080/actuator/health

# Check application info
curl http://localhost:8080/actuator/info
```

#### Services Available:
- **Application**: http://localhost:8080
- **Kafka UI**: http://localhost:8081
- **PostgreSQL**: localhost:5432
- **Redis**: localhost:6379
- **Kafka**: localhost:9093

#### Stop Services

```bash
# Stop all services
docker-compose down

# Stop and remove volumes (clean slate)
docker-compose down -v
```

---

### Option 2: Run Application Only (External Dependencies)

If you have PostgreSQL, Redis, and Kafka running elsewhere:

#### Step 1: Configure application.yml

Update `src/main/resources/application.yml` with your connection details.

#### Step 2: Build and Run

```bash
# Build
mvn clean package -DskipTests

# Run with default profile
java -jar target/flash-sale-*.jar

# Or run with AWS profile
java -jar target/flash-sale-*.jar --spring.profiles.active=aws
```

---

## AWS Production Deployment

### Architecture Overview

The Flash Sale system is designed for deployment in **AWS Mumbai Region (ap-south-1)** with:
- **ECS Fargate**: Container orchestration
- **RDS PostgreSQL**: Primary database (Multi-AZ)
- **ElastiCache Redis**: Distributed cache (Cluster mode)
- **MSK (Managed Streaming for Kafka)**: Event streaming
- **Application Load Balancer**: Traffic distribution
- **CloudWatch**: Monitoring and logging

### Deployment Steps

#### 1. Prepare AWS Infrastructure

##### 1.1 Create VPC and Networking

```bash
# Create VPC with 3 availability zones
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region ap-south-1

# Create subnets in 3 AZs (ap-south-1a, ap-south-1b, ap-south-1c)
# Public subnets: 10.0.1.0/24, 10.0.2.0/24, 10.0.3.0/24
# Private subnets: 10.0.11.0/24, 10.0.12.0/24, 10.0.13.0/24
```

##### 1.2 Create RDS PostgreSQL Database

```bash
# Create DB subnet group
aws rds create-db-subnet-group \
  --db-subnet-group-name flash-sale-db-subnet \
  --db-subnet-group-description "Flash Sale DB Subnet Group" \
  --subnet-ids subnet-xxx subnet-yyy subnet-zzz \
  --region ap-south-1

# Create RDS instance (Multi-AZ for high availability)
aws rds create-db-instance \
  --db-instance-identifier flash-sale-db \
  --db-instance-class db.r6g.xlarge \
  --engine postgres \
  --engine-version 16.1 \
  --master-username flashsale_user \
  --master-user-password YOUR_SECURE_PASSWORD \
  --allocated-storage 100 \
  --storage-type gp3 \
  --iops 3000 \
  --db-name flashsale \
  --vpc-security-group-ids sg-xxx \
  --db-subnet-group-name flash-sale-db-subnet \
  --multi-az \
  --backup-retention-period 7 \
  --region ap-south-1
```

**Recommended RDS Configuration:**
- Instance: `db.r6g.xlarge` (4 vCPU, 32 GB RAM)
- Storage: 100 GB gp3 with 3000 IOPS
- Multi-AZ: Enabled
- Backup retention: 7 days
- Max connections: 200

##### 1.3 Create ElastiCache Redis Cluster

```bash
# Create cache subnet group
aws elasticache create-cache-subnet-group \
  --cache-subnet-group-name flash-sale-redis-subnet \
  --cache-subnet-group-description "Flash Sale Redis Subnet Group" \
  --subnet-ids subnet-xxx subnet-yyy subnet-zzz \
  --region ap-south-1

# Create Redis replication group (Cluster mode enabled)
aws elasticache create-replication-group \
  --replication-group-id flash-sale-redis \
  --replication-group-description "Flash Sale Redis Cluster" \
  --engine redis \
  --engine-version 7.1 \
  --cache-node-type cache.r6g.xlarge \
  --num-node-groups 3 \
  --replicas-per-node-group 2 \
  --cache-subnet-group-name flash-sale-redis-subnet \
  --security-group-ids sg-xxx \
  --at-rest-encryption-enabled \
  --transit-encryption-enabled \
  --auth-token YOUR_REDIS_PASSWORD \
  --region ap-south-1
```

**Recommended Redis Configuration:**
- Node type: `cache.r6g.xlarge` (4 vCPU, 26.32 GB RAM)
- Shards: 3
- Replicas per shard: 2
- Total nodes: 9 (3 primary + 6 replicas)
- Memory: ~79 GB total
- Encryption: At-rest and in-transit

##### 1.4 Create MSK (Kafka) Cluster

```bash
# Create MSK cluster
aws kafka create-cluster \
  --cluster-name flash-sale-kafka \
  --kafka-version 3.5.1 \
  --number-of-broker-nodes 3 \
  --broker-node-group-info file://kafka-broker-config.json \
  --encryption-info "EncryptionAtRest={DataVolumeKMSKeyId=YOUR_KMS_KEY},EncryptionInTransit={ClientBroker=TLS,InCluster=true}" \
  --region ap-south-1
```

**kafka-broker-config.json:**
```json
{
  "InstanceType": "kafka.m5.xlarge",
  "ClientSubnets": [
    "subnet-xxx",
    "subnet-yyy",
    "subnet-zzz"
  ],
  "SecurityGroups": ["sg-xxx"],
  "StorageInfo": {
    "EbsStorageInfo": {
      "VolumeSize": 500
    }
  }
}
```

**Recommended MSK Configuration:**
- Broker type: `kafka.m5.xlarge` (4 vCPU, 16 GB RAM)
- Brokers: 3 (one per AZ)
- Storage: 500 GB per broker
- Replication factor: 3

##### 1.5 Store Secrets in AWS Secrets Manager

```bash
# Store database password
aws secretsmanager create-secret \
  --name flash-sale/db-password \
  --secret-string "YOUR_DB_PASSWORD" \
  --region ap-south-1

# Store Redis password
aws secretsmanager create-secret \
  --name flash-sale/redis-password \
  --secret-string "YOUR_REDIS_PASSWORD" \
  --region ap-south-1
```

#### 2. Build and Push Docker Image

##### 2.1 Create ECR Repository

```bash
# Create ECR repository
aws ecr create-repository \
  --repository-name flash-sale \
  --region ap-south-1

# Get login credentials
aws ecr get-login-password --region ap-south-1 | \
  docker login --username AWS --password-stdin YOUR_ACCOUNT_ID.dkr.ecr.ap-south-1.amazonaws.com
```

##### 2.2 Build and Push Image

```bash
# Build Docker image
docker build -t flash-sale:latest .

# Tag image
docker tag flash-sale:latest YOUR_ACCOUNT_ID.dkr.ecr.ap-south-1.amazonaws.com/flash-sale:latest

# Push to ECR
docker push YOUR_ACCOUNT_ID.dkr.ecr.ap-south-1.amazonaws.com/flash-sale:latest
```

#### 3. Deploy to ECS Fargate

##### 3.1 Create IAM Roles

**Task Execution Role** (for ECS to pull images and access secrets):

```bash
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://ecs-task-execution-role.json

aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

**Task Role** (for application to access AWS services):

```bash
aws iam create-role \
  --role-name flashSaleTaskRole \
  --assume-role-policy-document file://ecs-task-role.json

# Attach policies for CloudWatch, Secrets Manager
aws iam attach-role-policy \
  --role-name flashSaleTaskRole \
  --policy-arn arn:aws:iam::aws:policy/CloudWatchFullAccess
```

##### 3.2 Create ECS Cluster

```bash
aws ecs create-cluster \
  --cluster-name flash-sale-cluster \
  --region ap-south-1
```

##### 3.3 Register Task Definition

Update `aws-ecs-task-definition.json` with your account ID and endpoints, then:

```bash
aws ecs register-task-definition \
  --cli-input-json file://aws-ecs-task-definition.json \
  --region ap-south-1
```

##### 3.4 Create Application Load Balancer

```bash
# Create ALB
aws elbv2 create-load-balancer \
  --name flash-sale-alb \
  --subnets subnet-xxx subnet-yyy subnet-zzz \
  --security-groups sg-xxx \
  --region ap-south-1

# Create target group
aws elbv2 create-target-group \
  --name flash-sale-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id vpc-xxx \
  --health-check-path /actuator/health \
  --health-check-interval-seconds 30 \
  --health-check-timeout-seconds 5 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --target-type ip \
  --region ap-south-1

# Create listener
aws elbv2 create-listener \
  --load-balancer-arn YOUR_ALB_ARN \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=YOUR_TG_ARN \
  --region ap-south-1
```

##### 3.5 Create ECS Service

```bash
aws ecs create-service \
  --cluster flash-sale-cluster \
  --service-name flash-sale-service \
  --task-definition flash-sale-app \
  --desired-count 10 \
  --launch-type FARGATE \
  --platform-version LATEST \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-xxx,subnet-yyy,subnet-zzz],securityGroups=[sg-xxx],assignPublicIp=DISABLED}" \
  --load-balancers "targetGroupArn=YOUR_TG_ARN,containerName=flash-sale-app,containerPort=8080" \
  --health-check-grace-period-seconds 120 \
  --region ap-south-1
```

##### 3.6 Configure Auto Scaling

```bash
# Register scalable target (scale 10-50 tasks)
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/flash-sale-cluster/flash-sale-service \
  --min-capacity 10 \
  --max-capacity 50 \
  --region ap-south-1

# Create CPU-based scaling policy (target 70% CPU)
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/flash-sale-cluster/flash-sale-service \
  --policy-name flash-sale-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration file://scaling-policy.json \
  --region ap-south-1
```

**scaling-policy.json:**
```json
{
  "TargetValue": 70.0,
  "PredefinedMetricSpecification": {
    "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
  },
  "ScaleInCooldown": 300,
  "ScaleOutCooldown": 60
}
```

#### 4. Configure CloudWatch Monitoring

```bash
# Create log group
aws logs create-log-group \
  --log-group-name /ecs/flash-sale \
  --region ap-south-1

# Create CloudWatch dashboard
aws cloudwatch put-dashboard \
  --dashboard-name FlashSaleDashboard \
  --dashboard-body file://cloudwatch-dashboard.json \
  --region ap-south-1
```

---

## Configuration

### Environment Variables

#### Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `aws` |
| `DB_HOST` | Database hostname | `flash-sale-db.xxx.ap-south-1.rds.amazonaws.com` |
| `DB_PORT` | Database port | `5432` |
| `DB_NAME` | Database name | `flashsale` |
| `DB_USERNAME` | Database user | `flashsale_user` |
| `DB_PASSWORD` | Database password | (from Secrets Manager) |
| `REDIS_HOST` | Redis hostname | `flash-sale-redis.xxx.cache.amazonaws.com` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | (from Secrets Manager) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers | `broker1:9092,broker2:9092,broker3:9092` |
| `AWS_REGION` | AWS region | `ap-south-1` |

#### Optional Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `JAVA_OPTS` | JVM options | (see Dockerfile) |
| `SERVER_PORT` | Application port | `8080` |
| `MANAGEMENT_SERVER_PORT` | Actuator port | `8080` |

### Application Profiles

- **default**: Local development with minimal dependencies
- **aws**: Production deployment on AWS with all features enabled

---

## Monitoring and Troubleshooting

### Health Checks

```bash
# Application health
curl http://your-alb-endpoint/actuator/health

# Detailed health (requires authentication in production)
curl http://your-alb-endpoint/actuator/health/liveness
curl http://your-alb-endpoint/actuator/health/readiness
```

### Metrics

Application exposes Prometheus-compatible metrics at:
```
http://your-endpoint/actuator/metrics
```

Key metrics to monitor:
- `http_server_requests_seconds`: Request latency
- `reservation_requests_total`: Reservation attempts
- `reservation_success_total`: Successful reservations
- `inventory_stock_count`: Current stock levels
- `cache_hit_rate`: Redis cache effectiveness

### Logs

#### View ECS Logs

```bash
# View logs in CloudWatch
aws logs tail /ecs/flash-sale --follow --region ap-south-1

# Filter by error level
aws logs filter-log-events \
  --log-group-name /ecs/flash-sale \
  --filter-pattern "ERROR" \
  --region ap-south-1
```

#### View Docker Compose Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f app
docker-compose logs -f postgres
docker-compose logs -f redis
```

### Common Issues

#### 1. Application Won't Start

**Symptoms**: Container restarts repeatedly

**Troubleshooting**:
```bash
# Check logs
docker-compose logs app

# Common causes:
# - Database connection failed: Check DB_HOST, DB_PASSWORD
# - Redis connection failed: Check REDIS_HOST, REDIS_PASSWORD
# - Kafka connection failed: Check KAFKA_BOOTSTRAP_SERVERS
```

#### 2. High Latency

**Symptoms**: Requests taking >500ms

**Troubleshooting**:
- Check database connection pool exhaustion
- Check Redis cache hit rate
- Check CPU/Memory utilization
- Review slow query logs

#### 3. Database Connection Pool Exhausted

**Symptoms**: `Cannot get connection from pool` errors

**Resolution**:
```yaml
# Increase pool size in application.yml
datasource:
  hikari:
    maximum-pool-size: 100  # Increase from 50
```

#### 4. Redis Connection Issues

**Symptoms**: `Unable to connect to Redis` errors

**Resolution**:
- Verify Redis security group allows traffic from ECS tasks
- Check Redis auth token is correct
- Verify Redis cluster is healthy

---

## Performance Testing

### Load Testing with Gatling

```bash
# Run load test
mvn gatling:test

# View results
open target/gatling/results/index.html
```

### Capacity Planning

Based on SYSTEM_ARCHITECTURE_ULTRA_V2.md:

**Target Capacity**:
- 250,000 RPS reads (product availability)
- 25,000 RPS writes (reservations)
- Sub-150ms read latency (P99)
- Sub-120ms write latency (P99)

**Recommended Production Setup**:
- **ECS Tasks**: 10-50 tasks (auto-scaling)
- **RDS**: db.r6g.xlarge or larger
- **Redis**: 3 shards with 2 replicas each
- **Kafka**: 3 brokers (kafka.m5.xlarge)
- **ALB**: Cross-zone load balancing enabled

---

## Security Checklist

- [ ] Database password stored in AWS Secrets Manager
- [ ] Redis auth token enabled and secured
- [ ] Kafka encryption in-transit and at-rest enabled
- [ ] ECS tasks run in private subnets
- [ ] Security groups configured with least privilege
- [ ] CloudWatch logging enabled for audit trail
- [ ] IAM roles follow least privilege principle
- [ ] VPC endpoints configured for AWS services
- [ ] SSL/TLS enabled for all external connections
- [ ] Rate limiting configured and tested

---

## Cost Estimation (Monthly)

Estimated AWS costs for Mumbai region:

| Service | Configuration | Estimated Cost |
|---------|--------------|----------------|
| ECS Fargate | 10 tasks (2 vCPU, 4GB each) | $730 |
| RDS PostgreSQL | db.r6g.xlarge Multi-AZ | $520 |
| ElastiCache Redis | 9 nodes (cache.r6g.xlarge) | $1,620 |
| MSK Kafka | 3 brokers (kafka.m5.xlarge) | $810 |
| ALB | Standard Load Balancer | $35 |
| Data Transfer | 5 TB/month | $430 |
| CloudWatch | Logs and metrics | $50 |
| **Total** | | **~$4,195/month** |

*Note: Costs vary based on actual usage and AWS pricing changes.*

---

## Support and Maintenance

### Backup Strategy

- **RDS**: Automated daily backups (7-day retention)
- **PostgreSQL**: Manual snapshots before major changes
- **Redis**: No persistence (cache only, can be rebuilt)
- **Kafka**: 7-day message retention

### Update Strategy

1. Build new Docker image
2. Push to ECR with new tag
3. Update ECS task definition with new image
4. Deploy using blue/green deployment
5. Monitor CloudWatch for errors
6. Rollback if issues detected

### Disaster Recovery

- **RTO (Recovery Time Objective)**: 30 minutes
- **RPO (Recovery Point Objective)**: 5 minutes
- Multi-AZ deployment ensures high availability
- Cross-region backup snapshots stored in S3

---

## Additional Resources

- [SYSTEM_ARCHITECTURE_ULTRA_V2.md](SYSTEM_ARCHITECTURE_ULTRA_V2.md) - Comprehensive architecture
- [ARCHITECTURE_FLOWCHARTS.md](ARCHITECTURE_FLOWCHARTS.md) - System diagrams
- [README.md](README.md) - Project overview
- [AWS ECS Documentation](https://docs.aws.amazon.com/ecs/)
- [AWS RDS Documentation](https://docs.aws.amazon.com/rds/)
- [AWS ElastiCache Documentation](https://docs.aws.amazon.com/elasticache/)
- [AWS MSK Documentation](https://docs.aws.amazon.com/msk/)
