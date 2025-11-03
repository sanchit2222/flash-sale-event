#!/bin/bash

# Flash Sale System - ElastiCache Redis Setup Script
# This script creates the ElastiCache Redis cluster

set -e

# Check if required config files exist
if [ ! -f "infrastructure/vpc-config.json" ] || [ ! -f "infrastructure/security-groups-config.json" ]; then
    echo "Error: Configuration files not found. Please run setup scripts in order."
    exit 1
fi

# Load configuration
VPC_ID=$(cat infrastructure/vpc-config.json | grep '"vpcId"' | cut -d'"' -f4)
REGION=$(cat infrastructure/vpc-config.json | grep '"region"' | cut -d'"' -f4)
# Get private subnet IDs (lines 11 and 12 in the JSON)
PRIVATE_SUBNET_1=$(sed -n '11p' infrastructure/vpc-config.json | cut -d'"' -f4)
PRIVATE_SUBNET_2=$(sed -n '12p' infrastructure/vpc-config.json | cut -d'"' -f4)
REDIS_SG_ID=$(cat infrastructure/security-groups-config.json | grep '"redisSecurityGroup"' | cut -d'"' -f4)
PROJECT_NAME="flash-sale"

# Redis configuration (Free tier compatible)
CACHE_CLUSTER_ID="${PROJECT_NAME}-redis"
NODE_TYPE="cache.t3.micro"  # Free tier eligible
ENGINE_VERSION="7.0"
PARAM_GROUP_NAME="${PROJECT_NAME}-redis-params"

echo "========================================"
echo "Creating ElastiCache Redis Cluster"
echo "Region: $REGION"
echo "Node Type: $NODE_TYPE"
echo "========================================"
echo ""

# Create Cache Subnet Group
SUBNET_GROUP_NAME="${PROJECT_NAME}-redis-subnet-group"

echo "Step 1/4: Creating Cache Subnet Group..."
aws elasticache create-cache-subnet-group \
    --cache-subnet-group-name $SUBNET_GROUP_NAME \
    --cache-subnet-group-description "Subnet group for Flash Sale Redis" \
    --subnet-ids $PRIVATE_SUBNET_1 $PRIVATE_SUBNET_2 \
    --region $REGION \
    --tags "Key=Name,Value=${PROJECT_NAME}-redis-subnet-group" "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Subnet group already exists, using existing one)"

echo "✓ Cache Subnet Group ready: $SUBNET_GROUP_NAME"

# Create Parameter Group for Redis optimization
echo "Step 2/4: Creating Parameter Group..."
aws elasticache create-cache-parameter-group \
    --cache-parameter-group-name $PARAM_GROUP_NAME \
    --cache-parameter-group-family redis7 \
    --description "Custom parameter group for Flash Sale Redis" \
    --region $REGION \
    --tags "Key=Name,Value=${PROJECT_NAME}-redis-params" "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Parameter group already exists, using existing one)"

echo "✓ Parameter Group ready: $PARAM_GROUP_NAME"

# Modify parameters for performance
aws elasticache modify-cache-parameter-group \
    --cache-parameter-group-name $PARAM_GROUP_NAME \
    --parameter-name-values \
        "ParameterName=maxmemory-policy,ParameterValue=allkeys-lru" \
        "ParameterName=timeout,ParameterValue=300" \
        "ParameterName=tcp-keepalive,ParameterValue=300" \
    --region $REGION > /dev/null 2>&1 || true

echo "  - Parameters configured"

# Create Redis Cache Cluster (single node for free tier)
echo "Step 3/4: Creating Redis Cache Cluster..."
echo "  (This will take 5-10 minutes, please wait...)"

aws elasticache create-cache-cluster \
    --cache-cluster-id $CACHE_CLUSTER_ID \
    --cache-node-type $NODE_TYPE \
    --engine redis \
    --engine-version $ENGINE_VERSION \
    --num-cache-nodes 1 \
    --cache-parameter-group-name $PARAM_GROUP_NAME \
    --cache-subnet-group-name $SUBNET_GROUP_NAME \
    --security-group-ids $REDIS_SG_ID \
    --snapshot-retention-limit 5 \
    --snapshot-window "03:00-05:00" \
    --preferred-maintenance-window "mon:05:00-mon:07:00" \
    --auto-minor-version-upgrade \
    --region $REGION \
    --tags "Key=Name,Value=${PROJECT_NAME}-redis" "Key=Project,Value=${PROJECT_NAME}" "Key=Environment,Value=development" > /dev/null

echo "✓ Redis Cache Cluster creation initiated"
echo ""
echo "Waiting for cache cluster to become available..."

# Wait for cache cluster to be available
aws elasticache wait cache-cluster-available \
    --cache-cluster-id $CACHE_CLUSTER_ID \
    --region $REGION

echo "✓ Redis Cache Cluster is now available"

# Get Redis endpoint
echo "Step 4/4: Retrieving Redis endpoint..."
REDIS_ENDPOINT=$(aws elasticache describe-cache-clusters \
    --cache-cluster-id $CACHE_CLUSTER_ID \
    --show-cache-node-info \
    --region $REGION \
    --query 'CacheClusters[0].CacheNodes[0].Endpoint.Address' \
    --output text)

REDIS_PORT=$(aws elasticache describe-cache-clusters \
    --cache-cluster-id $CACHE_CLUSTER_ID \
    --show-cache-node-info \
    --region $REGION \
    --query 'CacheClusters[0].CacheNodes[0].Endpoint.Port' \
    --output text)

echo "✓ Endpoint retrieved: $REDIS_ENDPOINT:$REDIS_PORT"

# Save Redis configuration
cat > infrastructure/redis-config.json <<EOF
{
  "cacheClusterId": "$CACHE_CLUSTER_ID",
  "endpoint": "$REDIS_ENDPOINT",
  "port": $REDIS_PORT,
  "cacheSubnetGroup": "$SUBNET_GROUP_NAME",
  "parameterGroup": "$PARAM_GROUP_NAME",
  "engineVersion": "$ENGINE_VERSION",
  "nodeType": "$NODE_TYPE"
}
EOF

# Save configuration to AWS Secrets Manager
SECRET_NAME="${PROJECT_NAME}/redis/config"

echo "Storing configuration in AWS Secrets Manager..."
aws secretsmanager create-secret \
    --name $SECRET_NAME \
    --description "Redis configuration for Flash Sale System" \
    --secret-string "{\"endpoint\":\"$REDIS_ENDPOINT\",\"port\":$REDIS_PORT}" \
    --region $REGION \
    --tags "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Secret already exists, skipping...)"

echo "✓ Configuration stored in Secrets Manager"

echo ""
echo "========================================"
echo "✓ ElastiCache Redis Setup Completed!"
echo "========================================"
echo ""
echo "Configuration saved to: infrastructure/redis-config.json"
echo ""
echo "Summary:"
echo "  Cache Cluster: $CACHE_CLUSTER_ID"
echo "  Endpoint: $REDIS_ENDPOINT:$REDIS_PORT"
echo "  Node Type: $NODE_TYPE"
echo "  Engine Version: $ENGINE_VERSION"
echo ""
echo "NOTE: This is a single-node Redis cluster (free tier compatible)"
echo ""
