# Flash Sale System - AWS Infrastructure Deployment Summary

## Deployment Date
**Date:** 2025-11-02
**Region:** ap-south-1 (Mumbai)
**Account:** 095866558012

---

## Infrastructure Components

### 1. VPC and Networking ✅

**VPC ID:** `vpc-017588df73d2791c8`
**CIDR Block:** 10.0.0.0/16

#### Subnets

| Type | Subnet ID | CIDR | Availability Zone |
|------|-----------|------|-------------------|
| Public Subnet 1 | subnet-03dc3a466eae28739 | 10.0.1.0/24 | ap-south-1a |
| Public Subnet 2 | subnet-0557741f96f0d10df | 10.0.2.0/24 | ap-south-1b |
| Private Subnet 1 | subnet-0c7e9da84cd734277 | 10.0.10.0/24 | ap-south-1a |
| Private Subnet 2 | subnet-05a4116ce8259d369 | 10.0.11.0/24 | ap-south-1b |
| Database Subnet 1 | subnet-00f24ed4100cb7b09 | 10.0.20.0/24 | ap-south-1a |
| Database Subnet 2 | subnet-06f8c6ff3fb8cadeb | 10.0.21.0/24 | ap-south-1b |

#### Network Components

- **Internet Gateway:** igw-08cb8a0c26b158322
- **NAT Gateway 1:** nat-04e23678552004a3a (ap-south-1a)
- **NAT Gateway 2:** nat-0e4b88d095809aed7 (ap-south-1b)
- **Public Route Table:** rtb-0eab323e85ce4c8f0
- **Private Route Table 1:** rtb-00bbc634bc6b9102e
- **Private Route Table 2:** rtb-0389ebd7fd59a37c2

**Cost:** ~$60-70/month (NAT Gateways)

---

### 2. Security Groups ✅

| Name | Security Group ID | Purpose |
|------|-------------------|---------|
| flash-sale-alb-sg | sg-0d9cfe1e6fc1216b4 | Application Load Balancer |
| flash-sale-app-sg | sg-0b0f70620a7859354 | Application Servers |
| flash-sale-rds-sg | sg-0ded7b945d62a87f6 | RDS PostgreSQL Database |
| flash-sale-redis-sg | sg-08584c23b5334d904 | ElastiCache Redis |
| flash-sale-kafka-sg | sg-0d84ecdad6d622647 | Kafka Broker |

**Security Rules:**
- ALB: Allows HTTP (80) and HTTPS (443) from anywhere
- App: Allows 8080 from ALB
- RDS: Allows PostgreSQL (5432) from App
- Redis: Allows Redis (6379) from App
- Kafka: Allows 9092, 9094, 2181 from App

**Cost:** Free

---

### 3. RDS PostgreSQL Database ✅

**Instance Identifier:** `flash-sale-postgres`
**Instance Class:** db.t3.micro (Free Tier Eligible)
**Engine:** PostgreSQL 15.14
**Storage:** 20GB gp2 (expandable to 50GB)
**Multi-AZ:** No (Free Tier limitation)

**Connection Details:**
- **Endpoint:** flash-sale-postgres.cd2m8wkacytk.ap-south-1.rds.amazonaws.com
- **Port:** 5432
- **Database:** flashsaledb
- **Username:** flashsaleadmin
- **Password:** [Stored in infrastructure/rds-config.json and AWS Secrets Manager]
- **JDBC URL:** `jdbc:postgresql://flash-sale-postgres.cd2m8wkacytk.ap-south-1.rds.amazonaws.com:5432/flashsaledb`

**Features:**
- Automated backups (7 days retention)
- CloudWatch logs enabled
- Storage autoscaling up to 50GB
- Performance Insights: Disabled (Free Tier)

**Cost:** Free (Free Tier) or ~$15/month after free tier

---

### 4. ElastiCache Redis ✅

**Cache Cluster ID:** `flash-sale-redis`
**Node Type:** cache.t3.micro (Free Tier Eligible)
**Engine:** Redis 7.0
**Nodes:** 1 (Single node, no replication)

**Connection Details:**
- **Endpoint:** flash-sale-redis.s8ocwg.0001.aps1.cache.amazonaws.com
- **Port:** 6379
- **No Auth Token:** (Free tier does not support encryption in transit)

**Features:**
- Automated snapshots (5 days retention)
- Automatic minor version upgrades
- CloudWatch metrics integration

**Cost:** Free (Free Tier) or ~$15/month after free tier

---

### 5. Kafka (EC2-Hosted) ✅

**Deployment Type:** Self-hosted on EC2 (Docker)
**Instance ID:** i-045b01ff47731905f
**Instance Type:** t3.micro (Free Tier Eligible)
**Private IP:** 10.0.10.61

**Connection Details:**
- **Bootstrap Servers:** 10.0.10.61:9092
- **Zookeeper:** 10.0.10.61:2181

**Topics Created:**
| Topic Name | Partitions | Replication Factor |
|------------|------------|-------------------|
| flash-sale-reservations | 10 | 1 |
| flash-sale-purchase-confirmations | 10 | 1 |
| flash-sale-inventory-updates | 10 | 1 |
| flash-sale-expiry-events | 10 | 1 |

**SSH Access:**
```bash
ssh -i infrastructure/flash-sale-kafka-key.pem ec2-user@10.0.10.61
```

**Kafka Management:**
```bash
# View Kafka logs
docker logs kafka

# List topics
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# Describe topic
docker exec kafka kafka-topics --describe --topic flash-sale-reservations --bootstrap-server localhost:9092
```

**Cost:** Free (Free Tier) or ~$10/month after free tier

**Note:** This is a cost-effective alternative to AWS MSK ($300-500/month). Suitable for development and moderate production workloads. For high availability, consider upgrading to MSK.

---

### 6. AWS Secrets Manager

Credentials are stored securely in AWS Secrets Manager:

| Secret Name | Contains |
|-------------|----------|
| flash-sale/rds/credentials | PostgreSQL username, password, endpoint |
| flash-sale/redis/config | Redis endpoint, port |
| flash-sale/kafka/config | Kafka bootstrap servers, Zookeeper |

**Retrieve Secrets:**
```bash
# RDS credentials
aws secretsmanager get-secret-value --secret-id flash-sale/rds/credentials --region ap-south-1

# Redis config
aws secretsmanager get-secret-value --secret-id flash-sale/redis/config --region ap-south-1

# Kafka config
aws secretsmanager get-secret-value --secret-id flash-sale/kafka/config --region ap-south-1
```

**Cost:** $0.40/secret/month + $0.05 per 10,000 API calls

---

## Total Estimated Monthly Cost

### Free Tier (First 12 Months)
- VPC: Free
- Security Groups: Free
- RDS (db.t3.micro): **Free** (750 hours/month)
- ElastiCache (cache.t3.micro): **Free** (750 hours/month)
- EC2 for Kafka (t3.micro): **Free** (750 hours/month)
- NAT Gateways: **~$65/month** (Not free tier)
- Secrets Manager: **~$1.20/month**
- Data Transfer: Variable

**Total Free Tier Cost: ~$65-70/month** (mainly NAT Gateways)

### After Free Tier
- RDS: ~$15/month
- ElastiCache: ~$15/month
- EC2 for Kafka: ~$10/month
- NAT Gateways: ~$65/month
- Secrets Manager: ~$1.20/month
- Data Transfer: Variable

**Total After Free Tier: ~$105-110/month**

### Cost Optimization Tips
1. **Stop EC2 Kafka instance when not in use** to save costs
2. **Delete NAT Gateways** if public subnet access is not required (~$65/month savings)
3. **Use RDS and ElastiCache reserved instances** for 30-50% cost reduction in production
4. **Consider upgrading to MSK** only when you need high availability and scale

---

## Application Configuration

### Spring Boot Application

The application is configured to use the AWS infrastructure. Use the `aws` profile:

```bash
# Run with AWS profile
java -jar target/flash-sale-1.0.0-SNAPSHOT.jar --spring.profiles.active=aws

# Or set environment variable
export SPRING_PROFILES_ACTIVE=aws
java -jar target/flash-sale-1.0.0-SNAPSHOT.jar
```

**Configuration File:** `src/main/resources/application-aws.yml`

### Environment Variables

Set these environment variables for sensitive data:

```bash
export DB_PASSWORD="aiwoqxb3f1vzQXVmcG20tBzj8"
export AWS_ACCESS_KEY_ID="your-access-key"
export AWS_SECRET_ACCESS_KEY="your-secret-key"
export SPRING_PROFILES_ACTIVE="aws"
```

---

## Next Steps

### 1. Initialize Database Schema

The database is empty. You need to initialize it with tables:

```bash
# Option 1: Use Flyway migrations (recommended)
# Add Flyway migration scripts to src/main/resources/db/migration/

# Option 2: Use JPA to auto-create tables (development only)
# Change spring.jpa.hibernate.ddl-auto to 'create' or 'update' in application-aws.yml
```

### 2. Deploy Application

**Option A: Deploy to EC2**
1. Launch EC2 instance in private subnet (t3.micro or larger)
2. Install Java 17
3. Copy JAR file to instance
4. Run application with AWS profile

**Option B: Deploy to ECS/Fargate**
1. Create Docker image
2. Push to ECR
3. Create ECS task definition
4. Deploy to Fargate

**Option C: Deploy to Elastic Beanstalk**
1. Install EB CLI
2. Initialize Elastic Beanstalk application
3. Deploy JAR file

### 3. Set Up Application Load Balancer

Create an ALB to distribute traffic:
```bash
# Use the pre-created ALB security group: sg-0d9cfe1e6fc1216b4
# Attach to public subnets: subnet-03dc3a466eae28739, subnet-0557741f96f0d10df
# Target group: Point to application server on port 8080
```

### 4. Configure CloudWatch Dashboards

Create dashboards to monitor:
- Application metrics (requests/sec, latency, errors)
- RDS metrics (connections, CPU, storage)
- Redis metrics (cache hit rate, evictions)
- Kafka metrics (lag, throughput)

### 5. Set Up Alarms

Configure CloudWatch alarms for:
- High RDS CPU usage (> 80%)
- High application error rate (> 5%)
- Redis memory usage (> 90%)
- Kafka consumer lag (> 1000 messages)

### 6. Test the System

1. **Health Check:**
   ```bash
   curl http://your-alb-endpoint/actuator/health
   ```

2. **Load Testing:**
   - Use Apache JMeter or Gatling
   - Simulate 250k RPS read + 25k RPS write traffic
   - Monitor CloudWatch metrics

3. **Verify Kafka Topics:**
   ```bash
   ssh -i infrastructure/flash-sale-kafka-key.pem ec2-user@10.0.10.61
   docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic flash-sale-reservations --from-beginning
   ```

---

## Troubleshooting

### Cannot Connect to RDS
- Verify security group allows traffic from application subnet
- Check VPC routing tables
- Verify RDS is in correct subnets
- Test with: `psql -h <endpoint> -U flashsaleadmin -d flashsaledb`

### Cannot Connect to Redis
- Verify security group allows traffic on port 6379
- Check Redis endpoint is correct
- Test with: `redis-cli -h <endpoint> ping`

### Cannot Connect to Kafka
- Verify EC2 instance is running: `aws ec2 describe-instances --instance-ids i-045b01ff47731905f`
- Check Kafka is running: `ssh` into instance and run `docker ps`
- View Kafka logs: `docker logs kafka`

### Application Won't Start
- Check logs: `tail -f /var/log/flash-sale/flash-sale.log`
- Verify all environment variables are set
- Check application-aws.yml configuration
- Ensure Java 17 is installed: `java -version`

---

## Infrastructure Management Commands

### Start/Stop Resources to Save Costs

```bash
# Stop Kafka EC2 instance when not in use
aws ec2 stop-instances --instance-ids i-045b01ff47731905f --region ap-south-1

# Start Kafka EC2 instance
aws ec2 start-instances --instance-ids i-045b01ff47731905f --region ap-south-1

# Stop RDS instance (saves costs but you lose the endpoint)
aws rds stop-db-instance --db-instance-identifier flash-sale-postgres --region ap-south-1

# Start RDS instance
aws rds start-db-instance --db-instance-identifier flash-sale-postgres --region ap-south-1
```

### Delete Resources (WARNING: This will delete all data)

```bash
# Delete in reverse order to avoid dependency issues

# 1. Delete Kafka EC2 instance
aws ec2 terminate-instances --instance-ids i-045b01ff47731905f --region ap-south-1

# 2. Delete Redis cache cluster
aws elasticache delete-cache-cluster --cache-cluster-id flash-sale-redis --region ap-south-1

# 3. Delete RDS instance (remove --skip-final-snapshot to create backup)
aws rds delete-db-instance --db-instance-identifier flash-sale-postgres --skip-final-snapshot --region ap-south-1

# 4. Delete NAT Gateways (to save money)
aws ec2 delete-nat-gateway --nat-gateway-id nat-04e23678552004a3a --region ap-south-1
aws ec2 delete-nat-gateway --nat-gateway-id nat-0e4b88d095809aed7 --region ap-south-1

# 5. Release Elastic IPs
aws ec2 release-address --allocation-id eipalloc-0ce432886a181e4c3 --region ap-south-1
aws ec2 release-address --allocation-id eipalloc-0e141ae23f79bb772 --region ap-south-1

# Wait a few minutes for deletions to complete, then delete VPC and related resources
# (This is more complex and should be done through AWS Console or CloudFormation)
```

---

## Support and Documentation

- **AWS Documentation:** https://docs.aws.amazon.com/
- **Spring Boot Documentation:** https://docs.spring.io/spring-boot/
- **Kafka Documentation:** https://kafka.apache.org/documentation/
- **Project Documentation:** See AWS_SETUP.md and README.md

---

**Infrastructure setup completed successfully!** 🎉
