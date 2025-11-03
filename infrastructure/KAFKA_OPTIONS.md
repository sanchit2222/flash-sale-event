# Kafka Setup Options for Flash Sale System

## Important: Cost Consideration

AWS MSK (Managed Streaming for Apache Kafka) is **NOT free tier eligible** and has significant ongoing costs:

- **Minimum MSK configuration**: ~$300-500/month
  - 3 x kafka.t3.small brokers: ~$0.21/hour each = ~$450/month
  - Storage costs additional
  - Data transfer costs additional

## Option 1: AWS MSK (Production-Ready, Expensive)

Use the provided `05-setup-kafka-msk.sh` script to create a full MSK cluster.

**Pros:**
- Fully managed
- High availability
- Production-ready
- Automatic scaling
- Integrated with AWS services

**Cons:**
- Expensive (~$300-500/month minimum)
- Not free tier eligible
- Overkill for development/testing

**When to use:** Production deployments with serious traffic requirements

## Option 2: Self-Hosted Kafka on EC2 (Cost-Effective)

Run Kafka on a free-tier eligible EC2 instance (t2.micro or t3.micro).

**Pros:**
- Free tier eligible (first 12 months)
- Full control
- Suitable for development/testing
- Can be stopped when not in use

**Cons:**
- Manual setup and maintenance
- Single node (no high availability)
- Requires more operational knowledge

**Cost:** Free (free tier) or ~$10-15/month after free tier

**Setup:**
1. Launch t3.micro EC2 instance in your private subnet
2. Install Docker
3. Run Kafka using docker-compose
4. Configure security group to allow access from application servers

See `05-setup-kafka-ec2.sh` for automated setup.

## Option 3: Local Kafka (Development Only)

Run Kafka locally using Docker for development and testing.

**Pros:**
- Zero AWS costs
- Fast iteration
- Easy to reset/recreate

**Cons:**
- Not suitable for production
- Not accessible from AWS resources
- Limited to local development

**Setup:**
```bash
docker-compose -f docker-compose-kafka.yml up -d
```

## Recommendation

**For your current setup (free tier account):**

We recommend **Option 2: Self-Hosted Kafka on EC2** for now. This allows you to:
- Test the full system architecture
- Keep costs minimal
- Upgrade to MSK later when needed

**Migration path:**
1. Start with EC2-hosted Kafka for development (Option 2)
2. When ready for production, run `05-setup-kafka-msk.sh` to create MSK cluster
3. Update application.yml with new Kafka bootstrap servers
4. Redeploy application

## Decision Required

Please choose which option you'd like to proceed with:

1. **Setup MSK now** (~$300-500/month) - Run: `bash infrastructure/05-setup-kafka-msk.sh`
2. **Setup Kafka on EC2** (free tier / ~$10/month) - Run: `bash infrastructure/05-setup-kafka-ec2.sh`
3. **Skip for now** and use local Kafka for development

The setup scripts for both options have been created and are ready to use.
