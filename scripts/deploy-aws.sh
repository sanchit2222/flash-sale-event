#!/bin/bash

# Flash Sale System - AWS Deployment Script
# This script automates the deployment of Flash Sale system to AWS ECS Fargate

set -e

# Configuration
AWS_REGION="ap-south-1"
ECR_REPO_NAME="flash-sale"
ECS_CLUSTER_NAME="flash-sale-cluster"
ECS_SERVICE_NAME="flash-sale-service"
IMAGE_TAG="${1:-latest}"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}====================================="
echo "Flash Sale System - AWS Deployment"
echo "=====================================${NC}"
echo ""

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}Error: AWS CLI is not installed. Please install it first.${NC}"
    exit 1
fi

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Error: Docker is not installed. Please install Docker first.${NC}"
    exit 1
fi

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed. Please install Maven 3.9+ first.${NC}"
    exit 1
fi

# Check AWS credentials
echo -e "${YELLOW}Checking AWS credentials...${NC}"
if ! aws sts get-caller-identity &> /dev/null; then
    echo -e "${RED}Error: AWS credentials not configured. Run 'aws configure' first.${NC}"
    exit 1
fi

AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo -e "${GREEN}✓ AWS Account: ${AWS_ACCOUNT_ID}${NC}"

# Get ECR repository URI
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO_NAME}"

echo ""
echo -e "${YELLOW}Step 1: Building application JAR...${NC}"
mvn clean package -DskipTests
echo -e "${GREEN}✓ Application built successfully${NC}"

echo ""
echo -e "${YELLOW}Step 2: Building Docker image...${NC}"
docker build -t ${ECR_REPO_NAME}:${IMAGE_TAG} .
echo -e "${GREEN}✓ Docker image built: ${ECR_REPO_NAME}:${IMAGE_TAG}${NC}"

echo ""
echo -e "${YELLOW}Step 3: Logging in to ECR...${NC}"
aws ecr get-login-password --region ${AWS_REGION} | \
    docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
echo -e "${GREEN}✓ Logged in to ECR${NC}"

echo ""
echo -e "${YELLOW}Step 4: Tagging Docker image...${NC}"
docker tag ${ECR_REPO_NAME}:${IMAGE_TAG} ${ECR_URI}:${IMAGE_TAG}
docker tag ${ECR_REPO_NAME}:${IMAGE_TAG} ${ECR_URI}:latest
echo -e "${GREEN}✓ Image tagged${NC}"

echo ""
echo -e "${YELLOW}Step 5: Pushing image to ECR...${NC}"
docker push ${ECR_URI}:${IMAGE_TAG}
docker push ${ECR_URI}:latest
echo -e "${GREEN}✓ Image pushed to ECR${NC}"

echo ""
echo -e "${YELLOW}Step 6: Getting current task definition...${NC}"
TASK_DEFINITION=$(aws ecs describe-task-definition \
    --task-definition flash-sale-app \
    --region ${AWS_REGION} \
    --query 'taskDefinition' \
    --output json 2>/dev/null || echo "{}")

if [ "$TASK_DEFINITION" == "{}" ]; then
    echo -e "${YELLOW}No existing task definition found. Please create one first using:${NC}"
    echo -e "${BLUE}aws ecs register-task-definition --cli-input-json file://aws-ecs-task-definition.json${NC}"
    echo ""
    echo -e "${YELLOW}Skipping task update...${NC}"
else
    echo -e "${GREEN}✓ Found existing task definition${NC}"

    echo ""
    echo -e "${YELLOW}Step 7: Updating task definition with new image...${NC}"

    # Extract and modify task definition
    NEW_TASK_DEF=$(echo $TASK_DEFINITION | jq --arg IMAGE "${ECR_URI}:${IMAGE_TAG}" \
        'del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities, .registeredAt, .registeredBy) |
        .containerDefinitions[0].image = $IMAGE')

    # Register new task definition
    NEW_REVISION=$(aws ecs register-task-definition \
        --cli-input-json "$NEW_TASK_DEF" \
        --region ${AWS_REGION} \
        --query 'taskDefinition.revision' \
        --output text)

    echo -e "${GREEN}✓ New task definition registered: flash-sale-app:${NEW_REVISION}${NC}"

    echo ""
    echo -e "${YELLOW}Step 8: Updating ECS service...${NC}"
    aws ecs update-service \
        --cluster ${ECS_CLUSTER_NAME} \
        --service ${ECS_SERVICE_NAME} \
        --task-definition flash-sale-app:${NEW_REVISION} \
        --force-new-deployment \
        --region ${AWS_REGION} \
        --query 'service.serviceName' \
        --output text

    echo -e "${GREEN}✓ ECS service updated${NC}"

    echo ""
    echo -e "${YELLOW}Step 9: Waiting for deployment to complete...${NC}"
    echo "This may take 3-5 minutes..."

    aws ecs wait services-stable \
        --cluster ${ECS_CLUSTER_NAME} \
        --services ${ECS_SERVICE_NAME} \
        --region ${AWS_REGION}

    echo -e "${GREEN}✓ Deployment completed successfully${NC}"
fi

echo ""
echo -e "${GREEN}====================================="
echo "  Deployment Successful!"
echo "=====================================${NC}"
echo ""
echo "Image details:"
echo "  Repository: ${ECR_URI}"
echo "  Tag: ${IMAGE_TAG}"
echo ""
echo "Useful commands:"
echo "  View service: aws ecs describe-services --cluster ${ECS_CLUSTER_NAME} --services ${ECS_SERVICE_NAME} --region ${AWS_REGION}"
echo "  View tasks:   aws ecs list-tasks --cluster ${ECS_CLUSTER_NAME} --service ${ECS_SERVICE_NAME} --region ${AWS_REGION}"
echo "  View logs:    aws logs tail /ecs/flash-sale --follow --region ${AWS_REGION}"
echo ""
echo -e "${YELLOW}Note: Check the ALB endpoint to access your application${NC}"
