#!/bin/bash

# Flash Sale System - Security Groups Setup Script
# This script creates all necessary security groups for the infrastructure

set -e

# Check if VPC config exists
if [ ! -f "infrastructure/vpc-config.json" ]; then
    echo "Error: infrastructure/vpc-config.json not found. Please run 01-setup-vpc.sh first."
    exit 1
fi

# Load VPC configuration
VPC_ID=$(cat infrastructure/vpc-config.json | grep -o '"vpcId": "[^"]*' | cut -d'"' -f4)
REGION=$(cat infrastructure/vpc-config.json | grep -o '"region": "[^"]*' | cut -d'"' -f4)
PROJECT_NAME="flash-sale"

echo "========================================"
echo "Creating Security Groups"
echo "VPC ID: $VPC_ID"
echo "Region: $REGION"
echo "========================================"
echo ""

# Create Application Load Balancer Security Group
echo "Step 1/5: Creating ALB Security Group..."
ALB_SG_ID=$(aws ec2 create-security-group \
    --group-name ${PROJECT_NAME}-alb-sg \
    --description "Security group for Application Load Balancer" \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${PROJECT_NAME}-alb-sg},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'GroupId' \
    --output text)

echo "✓ ALB Security Group created: $ALB_SG_ID"

# Allow HTTP and HTTPS from anywhere
aws ec2 authorize-security-group-ingress \
    --group-id $ALB_SG_ID \
    --protocol tcp \
    --port 80 \
    --cidr 0.0.0.0/0 \
    --region $REGION > /dev/null

aws ec2 authorize-security-group-ingress \
    --group-id $ALB_SG_ID \
    --protocol tcp \
    --port 443 \
    --cidr 0.0.0.0/0 \
    --region $REGION > /dev/null

echo "  - Allowed HTTP (80) and HTTPS (443) from anywhere"

# Create Application Server Security Group
echo "Step 2/5: Creating Application Security Group..."
APP_SG_ID=$(aws ec2 create-security-group \
    --group-name ${PROJECT_NAME}-app-sg \
    --description "Security group for Application Servers" \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${PROJECT_NAME}-app-sg},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'GroupId' \
    --output text)

echo "✓ Application Security Group created: $APP_SG_ID"

# Allow traffic from ALB on port 8080
aws ec2 authorize-security-group-ingress \
    --group-id $APP_SG_ID \
    --protocol tcp \
    --port 8080 \
    --source-group $ALB_SG_ID \
    --region $REGION > /dev/null

echo "  - Allowed port 8080 from ALB"

# Create RDS PostgreSQL Security Group
echo "Step 3/5: Creating RDS Security Group..."
RDS_SG_ID=$(aws ec2 create-security-group \
    --group-name ${PROJECT_NAME}-rds-sg \
    --description "Security group for RDS PostgreSQL" \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${PROJECT_NAME}-rds-sg},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'GroupId' \
    --output text)

echo "✓ RDS Security Group created: $RDS_SG_ID"

# Allow PostgreSQL from application servers
aws ec2 authorize-security-group-ingress \
    --group-id $RDS_SG_ID \
    --protocol tcp \
    --port 5432 \
    --source-group $APP_SG_ID \
    --region $REGION > /dev/null

echo "  - Allowed PostgreSQL (5432) from application servers"

# Create ElastiCache Redis Security Group
echo "Step 4/5: Creating Redis Security Group..."
REDIS_SG_ID=$(aws ec2 create-security-group \
    --group-name ${PROJECT_NAME}-redis-sg \
    --description "Security group for ElastiCache Redis" \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${PROJECT_NAME}-redis-sg},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'GroupId' \
    --output text)

echo "✓ Redis Security Group created: $REDIS_SG_ID"

# Allow Redis from application servers
aws ec2 authorize-security-group-ingress \
    --group-id $REDIS_SG_ID \
    --protocol tcp \
    --port 6379 \
    --source-group $APP_SG_ID \
    --region $REGION > /dev/null

echo "  - Allowed Redis (6379) from application servers"

# Create MSK Kafka Security Group
echo "Step 5/5: Creating Kafka Security Group..."
KAFKA_SG_ID=$(aws ec2 create-security-group \
    --group-name ${PROJECT_NAME}-kafka-sg \
    --description "Security group for MSK Kafka" \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Name,Value=${PROJECT_NAME}-kafka-sg},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'GroupId' \
    --output text)

echo "✓ Kafka Security Group created: $KAFKA_SG_ID"

# Allow Kafka from application servers
aws ec2 authorize-security-group-ingress \
    --group-id $KAFKA_SG_ID \
    --protocol tcp \
    --port 9092 \
    --source-group $APP_SG_ID \
    --region $REGION > /dev/null

aws ec2 authorize-security-group-ingress \
    --group-id $KAFKA_SG_ID \
    --protocol tcp \
    --port 9094 \
    --source-group $APP_SG_ID \
    --region $REGION > /dev/null

aws ec2 authorize-security-group-ingress \
    --group-id $KAFKA_SG_ID \
    --protocol tcp \
    --port 2181 \
    --source-group $APP_SG_ID \
    --region $REGION > /dev/null

echo "  - Allowed Kafka (9092, 9094) and Zookeeper (2181) from application servers"

# Save security group configuration
cat > infrastructure/security-groups-config.json <<EOF
{
  "albSecurityGroup": "$ALB_SG_ID",
  "appSecurityGroup": "$APP_SG_ID",
  "rdsSecurityGroup": "$RDS_SG_ID",
  "redisSecurityGroup": "$REDIS_SG_ID",
  "kafkaSecurityGroup": "$KAFKA_SG_ID"
}
EOF

echo ""
echo "========================================"
echo "✓ Security Groups Setup Completed!"
echo "========================================"
echo ""
echo "Configuration saved to: infrastructure/security-groups-config.json"
echo ""
echo "Summary:"
echo "  ALB Security Group: $ALB_SG_ID"
echo "  Application Security Group: $APP_SG_ID"
echo "  RDS Security Group: $RDS_SG_ID"
echo "  Redis Security Group: $REDIS_SG_ID"
echo "  Kafka Security Group: $KAFKA_SG_ID"
echo ""
