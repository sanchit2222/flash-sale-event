#!/bin/bash

# Flash Sale System - VPC Setup Script
# This script creates the VPC, subnets, internet gateway, and route tables

set -e

# Configuration
REGION="ap-south-1"
VPC_CIDR="10.0.0.0/16"
PROJECT_NAME="flash-sale"

echo "========================================"
echo "Creating VPC for Flash Sale System"
echo "Region: $REGION"
echo "========================================"
echo ""

# Create VPC
echo "Step 1/15: Creating VPC..."
VPC_ID=$(aws ec2 create-vpc \
    --cidr-block $VPC_CIDR \
    --region $REGION \
    --tag-specifications "ResourceType=vpc,Tags=[{Key=Name,Value=${PROJECT_NAME}-vpc},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Vpc.VpcId' \
    --output text)

echo "✓ VPC created: $VPC_ID"

# Enable DNS hostnames
echo "Step 2/15: Enabling DNS hostnames..."
aws ec2 modify-vpc-attribute \
    --vpc-id $VPC_ID \
    --enable-dns-hostnames \
    --region $REGION

echo "✓ DNS hostnames enabled"

# Create Internet Gateway
echo "Step 3/15: Creating Internet Gateway..."
IGW_ID=$(aws ec2 create-internet-gateway \
    --region $REGION \
    --tag-specifications "ResourceType=internet-gateway,Tags=[{Key=Name,Value=${PROJECT_NAME}-igw},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'InternetGateway.InternetGatewayId' \
    --output text)

echo "✓ Internet Gateway created: $IGW_ID"

# Attach Internet Gateway to VPC
echo "Step 4/15: Attaching Internet Gateway to VPC..."
aws ec2 attach-internet-gateway \
    --vpc-id $VPC_ID \
    --internet-gateway-id $IGW_ID \
    --region $REGION

echo "✓ Internet Gateway attached"

# Create Public Subnets
echo "Step 5/15: Creating Public Subnet 1..."
PUBLIC_SUBNET_1_ID=$(aws ec2 create-subnet \
    --vpc-id $VPC_ID \
    --cidr-block 10.0.1.0/24 \
    --availability-zone ${REGION}a \
    --region $REGION \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=${PROJECT_NAME}-public-subnet-1},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Subnet.SubnetId' \
    --output text)

echo "✓ Public Subnet 1 created: $PUBLIC_SUBNET_1_ID (${REGION}a)"

echo "Step 6/15: Creating Public Subnet 2..."
PUBLIC_SUBNET_2_ID=$(aws ec2 create-subnet \
    --vpc-id $VPC_ID \
    --cidr-block 10.0.2.0/24 \
    --availability-zone ${REGION}b \
    --region $REGION \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=${PROJECT_NAME}-public-subnet-2},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Subnet.SubnetId' \
    --output text)

echo "✓ Public Subnet 2 created: $PUBLIC_SUBNET_2_ID (${REGION}b)"

# Create Private Subnets
echo "Step 7/15: Creating Private Subnet 1..."
PRIVATE_SUBNET_1_ID=$(aws ec2 create-subnet \
    --vpc-id $VPC_ID \
    --cidr-block 10.0.10.0/24 \
    --availability-zone ${REGION}a \
    --region $REGION \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=${PROJECT_NAME}-private-subnet-1},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Subnet.SubnetId' \
    --output text)

echo "✓ Private Subnet 1 created: $PRIVATE_SUBNET_1_ID (${REGION}a)"

echo "Step 8/15: Creating Private Subnet 2..."
PRIVATE_SUBNET_2_ID=$(aws ec2 create-subnet \
    --vpc-id $VPC_ID \
    --cidr-block 10.0.11.0/24 \
    --availability-zone ${REGION}b \
    --region $REGION \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=${PROJECT_NAME}-private-subnet-2},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Subnet.SubnetId' \
    --output text)

echo "✓ Private Subnet 2 created: $PRIVATE_SUBNET_2_ID (${REGION}b)"

# Create Database Subnets
echo "Step 9/15: Creating Database Subnet 1..."
DB_SUBNET_1_ID=$(aws ec2 create-subnet \
    --vpc-id $VPC_ID \
    --cidr-block 10.0.20.0/24 \
    --availability-zone ${REGION}a \
    --region $REGION \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=${PROJECT_NAME}-db-subnet-1},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Subnet.SubnetId' \
    --output text)

echo "✓ Database Subnet 1 created: $DB_SUBNET_1_ID (${REGION}a)"

echo "Step 10/15: Creating Database Subnet 2..."
DB_SUBNET_2_ID=$(aws ec2 create-subnet \
    --vpc-id $VPC_ID \
    --cidr-block 10.0.21.0/24 \
    --availability-zone ${REGION}b \
    --region $REGION \
    --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=${PROJECT_NAME}-db-subnet-2},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'Subnet.SubnetId' \
    --output text)

echo "✓ Database Subnet 2 created: $DB_SUBNET_2_ID (${REGION}b)"

# Create Public Route Table
echo "Step 11/15: Creating Public Route Table..."
PUBLIC_RT_ID=$(aws ec2 create-route-table \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=${PROJECT_NAME}-public-rt},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'RouteTable.RouteTableId' \
    --output text)

echo "✓ Public Route Table created: $PUBLIC_RT_ID"

# Add route to Internet Gateway
echo "Step 12/15: Adding route to Internet Gateway..."
aws ec2 create-route \
    --route-table-id $PUBLIC_RT_ID \
    --destination-cidr-block 0.0.0.0/0 \
    --gateway-id $IGW_ID \
    --region $REGION

echo "✓ Route added to Internet Gateway"

# Associate public subnets with public route table
echo "Step 13/15: Associating public subnets..."
aws ec2 associate-route-table \
    --route-table-id $PUBLIC_RT_ID \
    --subnet-id $PUBLIC_SUBNET_1_ID \
    --region $REGION

aws ec2 associate-route-table \
    --route-table-id $PUBLIC_RT_ID \
    --subnet-id $PUBLIC_SUBNET_2_ID \
    --region $REGION

echo "✓ Public subnets associated with route table"

# Allocate Elastic IPs for NAT Gateways
echo "Step 14/15: Allocating Elastic IPs for NAT Gateways..."
EIP_1_ID=$(aws ec2 allocate-address \
    --domain vpc \
    --region $REGION \
    --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=${PROJECT_NAME}-nat-eip-1},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'AllocationId' \
    --output text)

echo "✓ Elastic IP 1 allocated: $EIP_1_ID"

EIP_2_ID=$(aws ec2 allocate-address \
    --domain vpc \
    --region $REGION \
    --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=${PROJECT_NAME}-nat-eip-2},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'AllocationId' \
    --output text)

echo "✓ Elastic IP 2 allocated: $EIP_2_ID"

# Create NAT Gateways
echo "Step 15/15: Creating NAT Gateways..."
NAT_GW_1_ID=$(aws ec2 create-nat-gateway \
    --subnet-id $PUBLIC_SUBNET_1_ID \
    --allocation-id $EIP_1_ID \
    --region $REGION \
    --tag-specifications "ResourceType=natgateway,Tags=[{Key=Name,Value=${PROJECT_NAME}-nat-gw-1},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'NatGateway.NatGatewayId' \
    --output text)

echo "✓ NAT Gateway 1 created: $NAT_GW_1_ID"

NAT_GW_2_ID=$(aws ec2 create-nat-gateway \
    --subnet-id $PUBLIC_SUBNET_2_ID \
    --allocation-id $EIP_2_ID \
    --region $REGION \
    --tag-specifications "ResourceType=natgateway,Tags=[{Key=Name,Value=${PROJECT_NAME}-nat-gw-2},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'NatGateway.NatGatewayId' \
    --output text)

echo "✓ NAT Gateway 2 created: $NAT_GW_2_ID"

# Wait for NAT Gateways to become available
echo ""
echo "Waiting for NAT Gateways to become available (2-3 minutes)..."
aws ec2 wait nat-gateway-available --nat-gateway-ids $NAT_GW_1_ID --region $REGION &
aws ec2 wait nat-gateway-available --nat-gateway-ids $NAT_GW_2_ID --region $REGION &
wait

echo "✓ NAT Gateways are now available"

# Create Private Route Tables
echo "Creating Private Route Tables..."
PRIVATE_RT_1_ID=$(aws ec2 create-route-table \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=${PROJECT_NAME}-private-rt-1},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'RouteTable.RouteTableId' \
    --output text)

echo "✓ Private Route Table 1 created: $PRIVATE_RT_1_ID"

PRIVATE_RT_2_ID=$(aws ec2 create-route-table \
    --vpc-id $VPC_ID \
    --region $REGION \
    --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=${PROJECT_NAME}-private-rt-2},{Key=Project,Value=${PROJECT_NAME}}]" \
    --query 'RouteTable.RouteTableId' \
    --output text)

echo "✓ Private Route Table 2 created: $PRIVATE_RT_2_ID"

# Add routes to NAT Gateways
echo "Adding routes to NAT Gateways..."
aws ec2 create-route \
    --route-table-id $PRIVATE_RT_1_ID \
    --destination-cidr-block 0.0.0.0/0 \
    --nat-gateway-id $NAT_GW_1_ID \
    --region $REGION

aws ec2 create-route \
    --route-table-id $PRIVATE_RT_2_ID \
    --destination-cidr-block 0.0.0.0/0 \
    --nat-gateway-id $NAT_GW_2_ID \
    --region $REGION

echo "✓ Routes to NAT Gateways added"

# Associate private subnets with private route tables
echo "Associating private subnets with route tables..."
aws ec2 associate-route-table \
    --route-table-id $PRIVATE_RT_1_ID \
    --subnet-id $PRIVATE_SUBNET_1_ID \
    --region $REGION

aws ec2 associate-route-table \
    --route-table-id $PRIVATE_RT_2_ID \
    --subnet-id $PRIVATE_SUBNET_2_ID \
    --region $REGION

echo "✓ Private subnets associated with route tables"

# Save configuration to file
cat > infrastructure/vpc-config.json <<EOF
{
  "vpcId": "$VPC_ID",
  "region": "$REGION",
  "internetGatewayId": "$IGW_ID",
  "subnets": {
    "public": {
      "subnet1": "$PUBLIC_SUBNET_1_ID",
      "subnet2": "$PUBLIC_SUBNET_2_ID"
    },
    "private": {
      "subnet1": "$PRIVATE_SUBNET_1_ID",
      "subnet2": "$PRIVATE_SUBNET_2_ID"
    },
    "database": {
      "subnet1": "$DB_SUBNET_1_ID",
      "subnet2": "$DB_SUBNET_2_ID"
    }
  },
  "natGateways": {
    "natGateway1": "$NAT_GW_1_ID",
    "natGateway2": "$NAT_GW_2_ID"
  },
  "routeTables": {
    "public": "$PUBLIC_RT_ID",
    "private1": "$PRIVATE_RT_1_ID",
    "private2": "$PRIVATE_RT_2_ID"
  }
}
EOF

echo ""
echo "========================================"
echo "✓ VPC Setup Completed Successfully!"
echo "========================================"
echo ""
echo "Configuration saved to: infrastructure/vpc-config.json"
echo ""
echo "Summary:"
echo "  VPC ID: $VPC_ID"
echo "  Public Subnets: $PUBLIC_SUBNET_1_ID, $PUBLIC_SUBNET_2_ID"
echo "  Private Subnets: $PRIVATE_SUBNET_1_ID, $PRIVATE_SUBNET_2_ID"
echo "  Database Subnets: $DB_SUBNET_1_ID, $DB_SUBNET_2_ID"
echo "  NAT Gateways: $NAT_GW_1_ID, $NAT_GW_2_ID"
echo ""
