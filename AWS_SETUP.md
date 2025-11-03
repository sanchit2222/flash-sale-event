# AWS Infrastructure Setup Guide

This document provides step-by-step instructions for setting up the AWS infrastructure required for the Flash Sale system.

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Database Setup (RDS PostgreSQL)](#database-setup)
3. [Cache Setup (ElastiCache Redis)](#cache-setup)
4. [Message Queue Setup (MSK Kafka)](#message-queue-setup)
5. [Monitoring Setup (CloudWatch)](#monitoring-setup)
6. [IAM Roles and Permissions](#iam-roles)
7. [Application Deployment](#application-deployment)
8. [Scaling Configuration](#scaling-configuration)

---

## Prerequisites

- AWS Account with administrator access
- AWS CLI installed and configured
- Java 17+ installed
- Maven 3.6+ installed
- Basic understanding of AWS services

### Install AWS CLI

```bash
# macOS
brew install awscli

# Linux
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Windows
# Download and run installer from https://aws.amazon.com/cli/
```

### Configure AWS CLI

```bash
aws configure
# Enter your AWS Access Key ID
# Enter your AWS Secret Access Key
# Default region name: us-east-1
# Default output format: json
```

---

## Database Setup (RDS PostgreSQL)

### 1. Create VPC and Subnets (if not already exists)

```bash
# Create VPC
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=flashsale-vpc}]'

# Note the VPC ID from the output
export VPC_ID=<your-vpc-id>

# Create subnets in different availability zones
aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.1.0/24 --availability-zone us-east-1a
aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.2.0/24 --availability-zone us-east-1b
```

### 2. Create RDS PostgreSQL Database

```bash
# Create DB subnet group
aws rds create-db-subnet-group \
    --db-subnet-group-name flashsale-db-subnet \
    --db-subnet-group-description "Flash Sale DB Subnet Group" \
    --subnet-ids subnet-xxx subnet-yyy

# Create security group for database
aws ec2 create-security-group \
    --group-name flashsale-db-sg \
    --description "Security group for Flash Sale RDS" \
    --vpc-id $VPC_ID

# Allow PostgreSQL traffic (port 5432)
aws ec2 authorize-security-group-ingress \
    --group-id <security-group-id> \
    --protocol tcp \
    --port 5432 \
    --cidr 10.0.0.0/16

# Create RDS instance
aws rds create-db-instance \
    --db-instance-identifier flashsale-db \
    --db-instance-class db.r5.xlarge \
    --engine postgres \
    --engine-version 15.3 \
    --master-username flashsaleadmin \
    --master-user-password <strong-password> \
    --allocated-storage 100 \
    --storage-type gp3 \
    --storage-encrypted \
    --vpc-security-group-ids <security-group-id> \
    --db-subnet-group-name flashsale-db-subnet \
    --backup-retention-period 7 \
    --preferred-backup-window "03:00-04:00" \
    --preferred-maintenance-window "mon:04:00-mon:05:00" \
    --multi-az \
    --publicly-accessible false
```

### 3. Create Database Schema

```bash
# Connect to RDS instance
psql -h <rds-endpoint> -U flashsaleadmin -d postgres

# Create database
CREATE DATABASE flashsale;

# Connect to the database
\c flashsale

# Run schema creation scripts (DDL)
# Tables will be created automatically by JPA on first startup if using ddl-auto: create
# For production, use migration tools like Flyway or Liquibase
```

---

## Cache Setup (ElastiCache Redis)

### 1. Create ElastiCache Redis Cluster

```bash
# Create cache subnet group
aws elasticache create-cache-subnet-group \
    --cache-subnet-group-name flashsale-cache-subnet \
    --cache-subnet-group-description "Flash Sale Cache Subnet Group" \
    --subnet-ids subnet-xxx subnet-yyy

# Create security group for Redis
aws ec2 create-security-group \
    --group-name flashsale-redis-sg \
    --description "Security group for Flash Sale Redis" \
    --vpc-id $VPC_ID

# Allow Redis traffic (port 6379)
aws ec2 authorize-security-group-ingress \
    --group-id <security-group-id> \
    --protocol tcp \
    --port 6379 \
    --cidr 10.0.0.0/16

# Create Redis cluster
aws elasticache create-replication-group \
    --replication-group-id flashsale-redis \
    --replication-group-description "Flash Sale Redis Cluster" \
    --engine redis \
    --engine-version 7.0 \
    --cache-node-type cache.r5.xlarge \
    --num-cache-clusters 2 \
    --automatic-failover-enabled \
    --cache-subnet-group-name flashsale-cache-subnet \
    --security-group-ids <security-group-id> \
    --at-rest-encryption-enabled \
    --transit-encryption-enabled \
    --auth-token <strong-auth-token>
```

### 2. Configure Redis Parameters

Create a parameter group with optimized settings:

```bash
# Create parameter group
aws elasticache create-cache-parameter-group \
    --cache-parameter-group-name flashsale-redis-params \
    --cache-parameter-group-family redis7 \
    --description "Flash Sale Redis Parameters"

# Set parameters
aws elasticache modify-cache-parameter-group \
    --cache-parameter-group-name flashsale-redis-params \
    --parameter-name-values \
        "ParameterName=maxmemory-policy,ParameterValue=allkeys-lru" \
        "ParameterName=timeout,ParameterValue=300"
```

---

## Message Queue Setup (MSK Kafka)

### 1. Create MSK Kafka Cluster

```bash
# Create configuration
aws kafka create-configuration \
    --name flashsale-kafka-config \
    --server-properties file://kafka-config.properties \
    --kafka-versions "3.5.1"

# Create MSK cluster
aws kafka create-cluster \
    --cluster-name flashsale-kafka \
    --broker-node-group-info file://broker-info.json \
    --kafka-version "3.5.1" \
    --number-of-broker-nodes 3 \
    --enhanced-monitoring PER_TOPIC_PER_PARTITION \
    --encryption-info "EncryptionInTransit={ClientBroker=TLS,InCluster=true}" \
    --configuration-info "Arn=<config-arn>,Revision=1"
```

**broker-info.json:**
```json
{
  "InstanceType": "kafka.m5.large",
  "ClientSubnets": [
    "subnet-xxx",
    "subnet-yyy",
    "subnet-zzz"
  ],
  "SecurityGroups": ["<security-group-id>"],
  "StorageInfo": {
    "EbsStorageInfo": {
      "VolumeSize": 100
    }
  }
}
```

**kafka-config.properties:**
```properties
auto.create.topics.enable=false
default.replication.factor=3
min.insync.replicas=2
num.partitions=10
log.retention.hours=168
compression.type=snappy
```

### 2. Create Kafka Topics

```bash
# Get bootstrap servers
aws kafka get-bootstrap-brokers --cluster-arn <cluster-arn>

# Create topics
kafka-topics.sh --bootstrap-server <bootstrap-servers> \
    --create --topic flash-sale-reservations \
    --partitions 10 --replication-factor 3 \
    --config min.insync.replicas=2 \
    --config retention.ms=604800000

kafka-topics.sh --bootstrap-server <bootstrap-servers> \
    --create --topic flash-sale-inventory-updates \
    --partitions 10 --replication-factor 3 \
    --config min.insync.replicas=2

kafka-topics.sh --bootstrap-server <bootstrap-servers> \
    --create --topic flash-sale-orders \
    --partitions 10 --replication-factor 3 \
    --config min.insync.replicas=2
```

---

## Monitoring Setup (CloudWatch)

### 1. Create CloudWatch Dashboard

```bash
aws cloudwatch put-dashboard \
    --dashboard-name FlashSaleDashboard \
    --dashboard-body file://cloudwatch-dashboard.json
```

**cloudwatch-dashboard.json:**
```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["FlashSale", "flashsale.reservation.success"],
          [".", "flashsale.reservation.failure"]
        ],
        "period": 60,
        "stat": "Sum",
        "region": "us-east-1",
        "title": "Reservation Metrics"
      }
    },
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["FlashSale", "flashsale.inventory.available", {"stat": "Average"}]
        ],
        "period": 60,
        "stat": "Average",
        "region": "us-east-1",
        "title": "Inventory Levels"
      }
    },
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["FlashSale", "flashsale.reservation.latency"]
        ],
        "period": 60,
        "stat": "p99",
        "region": "us-east-1",
        "title": "API Latency (P99)"
      }
    }
  ]
}
```

### 2. Create CloudWatch Alarms

```bash
# High error rate alarm
aws cloudwatch put-metric-alarm \
    --alarm-name flashsale-high-error-rate \
    --alarm-description "Alert when error rate exceeds 5%" \
    --metric-name flashsale.reservation.failure \
    --namespace FlashSale \
    --statistic Sum \
    --period 60 \
    --evaluation-periods 2 \
    --threshold 50 \
    --comparison-operator GreaterThanThreshold

# High latency alarm
aws cloudwatch put-metric-alarm \
    --alarm-name flashsale-high-latency \
    --alarm-description "Alert when P99 latency exceeds 500ms" \
    --metric-name flashsale.reservation.latency \
    --namespace FlashSale \
    --statistic p99 \
    --period 60 \
    --evaluation-periods 2 \
    --threshold 500 \
    --comparison-operator GreaterThanThreshold

# Stock out alarm
aws cloudwatch put-metric-alarm \
    --alarm-name flashsale-stock-out \
    --alarm-description "Alert when products go out of stock" \
    --metric-name flashsale.inventory.stockout \
    --namespace FlashSale \
    --statistic Sum \
    --period 60 \
    --evaluation-periods 1 \
    --threshold 1 \
    --comparison-operator GreaterThanOrEqualToThreshold
```

---

## IAM Roles and Permissions

### 1. Create IAM Role for Application

```bash
# Create role
aws iam create-role \
    --role-name FlashSaleAppRole \
    --assume-role-policy-document file://trust-policy.json

# Attach policies
aws iam attach-role-policy \
    --role-name FlashSaleAppRole \
    --policy-arn arn:aws:iam::aws:policy/CloudWatchFullAccess

aws iam attach-role-policy \
    --role-name FlashSaleAppRole \
    --policy-arn arn:aws:iam::aws:policy/AmazonMSKFullAccess
```

**trust-policy.json:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

### 2. Create Custom Policy for Application

```bash
aws iam create-policy \
    --policy-name FlashSaleAppPolicy \
    --policy-document file://app-policy.json
```

**app-policy.json:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData",
        "cloudwatch:GetMetricStatistics",
        "cloudwatch:ListMetrics"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "rds:DescribeDBInstances",
        "rds:DescribeDBClusters"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "elasticache:DescribeCacheClusters",
        "elasticache:DescribeReplicationGroups"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kafka:DescribeCluster",
        "kafka:GetBootstrapBrokers",
        "kafka:ListClusters"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## Application Deployment

### 1. Build Application

```bash
# Clone repository
git clone <repository-url>
cd flash-sale

# Build with Maven
mvn clean package -DskipTests

# Build Docker image
docker build -t flashsale-app:latest .
```

### 2. Deploy on EC2

```bash
# Launch EC2 instance
aws ec2 run-instances \
    --image-id ami-0c55b159cbfafe1f0 \
    --instance-type m5.xlarge \
    --key-name <your-key-pair> \
    --security-group-ids <security-group-id> \
    --subnet-id <subnet-id> \
    --iam-instance-profile Name=FlashSaleAppRole \
    --user-data file://user-data.sh \
    --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=flashsale-app}]'
```

**user-data.sh:**
```bash
#!/bin/bash
yum update -y
yum install -y java-17-amazon-corretto docker

# Start Docker
systemctl start docker
systemctl enable docker

# Pull and run application
docker pull flashsale-app:latest
docker run -d \
    -p 8080:8080 \
    -e DB_PASSWORD=<db-password> \
    -e REDIS_PASSWORD=<redis-password> \
    -e AWS_REGION=us-east-1 \
    flashsale-app:latest
```

### 3. Deploy on ECS/EKS (Alternative)

Create ECS task definition or Kubernetes deployment manifests as needed.

---

## Scaling Configuration

### 1. Application Auto Scaling

```bash
# Create Auto Scaling group
aws autoscaling create-auto-scaling-group \
    --auto-scaling-group-name flashsale-asg \
    --launch-configuration-name flashsale-lc \
    --min-size 3 \
    --max-size 20 \
    --desired-capacity 5 \
    --vpc-zone-identifier "subnet-xxx,subnet-yyy" \
    --health-check-type ELB \
    --health-check-grace-period 300 \
    --target-group-arns <target-group-arn>

# Create scaling policies
aws autoscaling put-scaling-policy \
    --auto-scaling-group-name flashsale-asg \
    --policy-name scale-up \
    --scaling-adjustment 2 \
    --adjustment-type ChangeInCapacity

aws autoscaling put-scaling-policy \
    --auto-scaling-group-name flashsale-asg \
    --policy-name scale-down \
    --scaling-adjustment -1 \
    --adjustment-type ChangeInCapacity
```

### 2. Database Scaling

- Enable RDS read replicas for read-heavy workloads
- Consider Aurora for automatic scaling
- Set up connection pooling (already configured in application)

### 3. Cache Scaling

- Use Redis cluster mode for horizontal scaling
- Add more cache nodes as needed
- Monitor memory usage and eviction rates

---

## Environment Variables

Set these environment variables in your deployment:

```bash
# Database
export DB_PASSWORD=<your-db-password>

# Redis
export REDIS_PASSWORD=<your-redis-password>

# AWS
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=<your-access-key>
export AWS_SECRET_ACCESS_KEY=<your-secret-key>

# Application
export SPRING_PROFILES_ACTIVE=prod
```

---

## Verification

### Test Database Connection
```bash
psql -h <rds-endpoint> -U flashsaleadmin -d flashsale -c "SELECT 1;"
```

### Test Redis Connection
```bash
redis-cli -h <redis-endpoint> -a <auth-token> PING
```

### Test Kafka Connection
```bash
kafka-topics.sh --bootstrap-server <bootstrap-servers> --list
```

### Test Application Health
```bash
curl http://<application-endpoint>:8080/actuator/health
```

---

## Cost Optimization

1. **Use Reserved Instances** for predictable workloads
2. **Enable Auto Scaling** to scale down during low traffic
3. **Use Spot Instances** for non-critical workloads
4. **Monitor CloudWatch metrics** to rightsize resources
5. **Set up budget alerts** to track spending

---

## Security Best Practices

1. **Enable encryption** at rest and in transit for all services
2. **Use VPC** to isolate resources
3. **Implement security groups** with least privilege access
4. **Rotate credentials** regularly
5. **Enable CloudTrail** for audit logging
6. **Use AWS Secrets Manager** for sensitive configuration

---

## Support

For issues or questions, contact the Flash Sale Team or refer to:
- AWS Documentation: https://docs.aws.amazon.com/
- Spring Boot Documentation: https://spring.io/projects/spring-boot
- Application README: README.md
