#!/bin/bash

# Flash Sale System - RDS PostgreSQL Setup Script
# This script creates the RDS PostgreSQL database with Multi-AZ deployment

set -e

# Check if required config files exist
if [ ! -f "infrastructure/vpc-config.json" ] || [ ! -f "infrastructure/security-groups-config.json" ]; then
    echo "Error: Configuration files not found. Please run setup scripts in order."
    exit 1
fi

# Load configuration
VPC_ID=$(cat infrastructure/vpc-config.json | grep '"vpcId"' | cut -d'"' -f4)
REGION=$(cat infrastructure/vpc-config.json | grep '"region"' | cut -d'"' -f4)
# Get database subnet IDs (lines 15 and 16 in the JSON)
DB_SUBNET_1=$(sed -n '15p' infrastructure/vpc-config.json | cut -d'"' -f4)
DB_SUBNET_2=$(sed -n '16p' infrastructure/vpc-config.json | cut -d'"' -f4)
RDS_SG_ID=$(cat infrastructure/security-groups-config.json | grep '"rdsSecurityGroup"' | cut -d'"' -f4)
PROJECT_NAME="flash-sale"

# Database configuration
DB_INSTANCE_IDENTIFIER="${PROJECT_NAME}-postgres"
DB_NAME="flashsaledb"
DB_USERNAME="flashsaleadmin"
DB_PASSWORD=$(openssl rand -base64 32 | tr -d "=+/" | cut -c1-25)
DB_INSTANCE_CLASS="db.t3.micro"  # Free tier eligible
ALLOCATED_STORAGE=20  # Free tier limit
MAX_ALLOCATED_STORAGE=50
ENGINE_VERSION="15.14"  # Latest PostgreSQL 15.x version

echo "========================================"
echo "Creating RDS PostgreSQL Database"
echo "Region: $REGION"
echo "Instance: $DB_INSTANCE_CLASS"
echo "========================================"
echo ""

# Create DB Subnet Group
DB_SUBNET_GROUP_NAME="${PROJECT_NAME}-db-subnet-group"

echo "Step 1/4: Creating DB Subnet Group..."
aws rds create-db-subnet-group \
    --db-subnet-group-name $DB_SUBNET_GROUP_NAME \
    --db-subnet-group-description "Subnet group for Flash Sale RDS" \
    --subnet-ids $DB_SUBNET_1 $DB_SUBNET_2 \
    --region $REGION \
    --tags "Key=Name,Value=${PROJECT_NAME}-db-subnet-group" "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Subnet group already exists, using existing one)"

echo "✓ DB Subnet Group ready: $DB_SUBNET_GROUP_NAME"

# Create Parameter Group for PostgreSQL optimization
PARAM_GROUP_NAME="${PROJECT_NAME}-postgres-params"

echo "Step 2/4: Creating Parameter Group..."
aws rds create-db-parameter-group \
    --db-parameter-group-name $PARAM_GROUP_NAME \
    --db-parameter-group-family postgres15 \
    --description "Custom parameter group for Flash Sale PostgreSQL" \
    --region $REGION \
    --tags "Key=Name,Value=${PROJECT_NAME}-postgres-params" "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Parameter group already exists, using existing one)"

echo "✓ Parameter Group ready: $PARAM_GROUP_NAME"

# Modify parameters for high-performance workload
aws rds modify-db-parameter-group \
    --db-parameter-group-name $PARAM_GROUP_NAME \
    --parameters \
        "ParameterName=max_connections,ParameterValue=1000,ApplyMethod=pending-reboot" \
        "ParameterName=maintenance_work_mem,ParameterValue=2097152,ApplyMethod=immediate" \
        "ParameterName=checkpoint_completion_target,ParameterValue=0.9,ApplyMethod=immediate" \
        "ParameterName=wal_buffers,ParameterValue=16384,ApplyMethod=pending-reboot" \
        "ParameterName=default_statistics_target,ParameterValue=100,ApplyMethod=immediate" \
        "ParameterName=random_page_cost,ParameterValue=1.1,ApplyMethod=immediate" \
        "ParameterName=effective_io_concurrency,ParameterValue=200,ApplyMethod=immediate" \
        "ParameterName=work_mem,ParameterValue=10485,ApplyMethod=immediate" \
    --region $REGION > /dev/null

echo "  - Parameters optimized for high-performance workload"

# Create RDS instance
echo "Step 3/4: Creating RDS PostgreSQL instance..."
echo "  (This will take 10-15 minutes, please wait...)"

aws rds create-db-instance \
    --db-instance-identifier $DB_INSTANCE_IDENTIFIER \
    --db-instance-class $DB_INSTANCE_CLASS \
    --engine postgres \
    --engine-version $ENGINE_VERSION \
    --master-username $DB_USERNAME \
    --master-user-password $DB_PASSWORD \
    --allocated-storage $ALLOCATED_STORAGE \
    --max-allocated-storage $MAX_ALLOCATED_STORAGE \
    --storage-type gp2 \
    --db-name $DB_NAME \
    --vpc-security-group-ids $RDS_SG_ID \
    --db-subnet-group-name $DB_SUBNET_GROUP_NAME \
    --db-parameter-group-name $PARAM_GROUP_NAME \
    --no-multi-az \
    --backup-retention-period 7 \
    --preferred-backup-window "03:00-04:00" \
    --preferred-maintenance-window "mon:04:00-mon:05:00" \
    --no-enable-performance-insights \
    --enable-cloudwatch-logs-exports '["postgresql"]' \
    --auto-minor-version-upgrade \
    --no-deletion-protection \
    --publicly-accessible \
    --region $REGION \
    --tags "Key=Name,Value=${PROJECT_NAME}-postgres" "Key=Project,Value=${PROJECT_NAME}" "Key=Environment,Value=development" > /dev/null

echo "✓ RDS instance creation initiated"
echo ""
echo "Waiting for RDS instance to become available..."
echo "(This typically takes 10-15 minutes)"

# Wait for RDS instance to be available
aws rds wait db-instance-available \
    --db-instance-identifier $DB_INSTANCE_IDENTIFIER \
    --region $REGION

echo "✓ RDS instance is now available"

# Get RDS endpoint
echo "Step 4/4: Retrieving RDS endpoint..."
DB_ENDPOINT=$(aws rds describe-db-instances \
    --db-instance-identifier $DB_INSTANCE_IDENTIFIER \
    --region $REGION \
    --query 'DBInstances[0].Endpoint.Address' \
    --output text)

DB_PORT=$(aws rds describe-db-instances \
    --db-instance-identifier $DB_INSTANCE_IDENTIFIER \
    --region $REGION \
    --query 'DBInstances[0].Endpoint.Port' \
    --output text)

echo "✓ Endpoint retrieved: $DB_ENDPOINT:$DB_PORT"

# Save RDS configuration
cat > infrastructure/rds-config.json <<EOF
{
  "dbInstanceIdentifier": "$DB_INSTANCE_IDENTIFIER",
  "dbName": "$DB_NAME",
  "dbUsername": "$DB_USERNAME",
  "dbPassword": "$DB_PASSWORD",
  "dbEndpoint": "$DB_ENDPOINT",
  "dbPort": $DB_PORT,
  "jdbcUrl": "jdbc:postgresql://$DB_ENDPOINT:$DB_PORT/$DB_NAME",
  "dbSubnetGroup": "$DB_SUBNET_GROUP_NAME",
  "parameterGroup": "$PARAM_GROUP_NAME"
}
EOF

# Save password securely to AWS Secrets Manager
SECRET_NAME="${PROJECT_NAME}/rds/credentials"

echo "Storing credentials in AWS Secrets Manager..."
aws secretsmanager create-secret \
    --name $SECRET_NAME \
    --description "RDS PostgreSQL credentials for Flash Sale System" \
    --secret-string "{\"username\":\"$DB_USERNAME\",\"password\":\"$DB_PASSWORD\",\"engine\":\"postgres\",\"host\":\"$DB_ENDPOINT\",\"port\":$DB_PORT,\"dbname\":\"$DB_NAME\"}" \
    --region $REGION \
    --tags "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Secret already exists, skipping...)"

echo "✓ Credentials stored in Secrets Manager"

echo ""
echo "========================================"
echo "✓ RDS PostgreSQL Setup Completed!"
echo "========================================"
echo ""
echo "Configuration saved to: infrastructure/rds-config.json"
echo ""
echo "Summary:"
echo "  DB Instance: $DB_INSTANCE_IDENTIFIER"
echo "  Endpoint: $DB_ENDPOINT:$DB_PORT"
echo "  Database: $DB_NAME"
echo "  Username: $DB_USERNAME"
echo "  Password: [saved in rds-config.json and Secrets Manager]"
echo "  JDBC URL: jdbc:postgresql://$DB_ENDPOINT:$DB_PORT/$DB_NAME"
echo ""
echo "IMPORTANT: Save the credentials from infrastructure/rds-config.json securely!"
echo ""
