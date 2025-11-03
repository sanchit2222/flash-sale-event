#!/bin/bash

# Flash Sale System - Kafka on EC2 Setup Script
# This script creates an EC2 instance running Kafka (free tier compatible)

set -e

# Check if required config files exist
if [ ! -f "infrastructure/vpc-config.json" ] || [ ! -f "infrastructure/security-groups-config.json" ]; then
    echo "Error: Configuration files not found. Please run setup scripts in order."
    exit 1
fi

# Load configuration
VPC_ID=$(cat infrastructure/vpc-config.json | grep '"vpcId"' | cut -d'"' -f4)
REGION=$(cat infrastructure/vpc-config.json | grep '"region"' | cut -d'"' -f4)
PRIVATE_SUBNET_1=$(sed -n '11p' infrastructure/vpc-config.json | cut -d'"' -f4)
KAFKA_SG_ID=$(cat infrastructure/security-groups-config.json | grep '"kafkaSecurityGroup"' | cut -d'"' -f4)
PROJECT_NAME="flash-sale"

# EC2 configuration
INSTANCE_TYPE="t3.micro"  # Free tier eligible
AMI_ID="ami-0c2af51e265bd5e0e"  # Amazon Linux 2023 in ap-south-1
KEY_NAME="${PROJECT_NAME}-kafka-key"

echo "========================================"
echo "Creating Kafka on EC2"
echo "Region: $REGION"
echo "Instance Type: $INSTANCE_TYPE"
echo "========================================"
echo ""

# Create key pair for SSH access
echo "Step 1/4: Creating SSH key pair..."
aws ec2 create-key-pair \
    --key-name $KEY_NAME \
    --region $REGION \
    --query 'KeyMaterial' \
    --output text > infrastructure/${KEY_NAME}.pem 2>&1 || echo "  (Key pair already exists)"

if [ -f "infrastructure/${KEY_NAME}.pem" ]; then
    chmod 400 infrastructure/${KEY_NAME}.pem
    echo "✓ SSH key saved to: infrastructure/${KEY_NAME}.pem"
else
    echo "✓ Using existing key pair: $KEY_NAME"
fi

# Create user data script for Kafka installation
cat > infrastructure/kafka-user-data.sh <<'USERDATA'
#!/bin/bash
# Install Docker
yum update -y
yum install -y docker
systemctl start docker
systemctl enable docker
usermod -aG docker ec2-user

# Install Docker Compose
curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

# Create Kafka docker-compose file
mkdir -p /home/ec2-user/kafka
cat > /home/ec2-user/kafka/docker-compose.yml <<'EOF'
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    hostname: zookeeper
    container_name: zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    volumes:
      - zookeeper-data:/var/lib/zookeeper/data
      - zookeeper-logs:/var/lib/zookeeper/log

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    hostname: kafka
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
      - "9094:9094"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false'
      KAFKA_NUM_PARTITIONS: 10
      KAFKA_DEFAULT_REPLICATION_FACTOR: 1
      KAFKA_LOG_RETENTION_HOURS: 168
      KAFKA_LOG_SEGMENT_BYTES: 1073741824
      KAFKA_COMPRESSION_TYPE: producer
    volumes:
      - kafka-data:/var/lib/kafka/data

volumes:
  zookeeper-data:
  zookeeper-logs:
  kafka-data:
EOF

# Start Kafka
cd /home/ec2-user/kafka
/usr/local/bin/docker-compose up -d

# Create topics
sleep 30
docker exec kafka kafka-topics --create --topic flash-sale-reservations --bootstrap-server localhost:9092 --partitions 10 --replication-factor 1
docker exec kafka kafka-topics --create --topic flash-sale-purchase-confirmations --bootstrap-server localhost:9092 --partitions 10 --replication-factor 1
docker exec kafka kafka-topics --create --topic flash-sale-inventory-updates --bootstrap-server localhost:9092 --partitions 10 --replication-factor 1
docker exec kafka kafka-topics --create --topic flash-sale-expiry-events --bootstrap-server localhost:9092 --partitions 10 --replication-factor 1

echo "Kafka setup completed" > /home/ec2-user/kafka-setup-complete.txt
USERDATA

echo "Step 2/4: Creating EC2 instance with Kafka..."

INSTANCE_ID=$(aws ec2 run-instances \
    --image-id $AMI_ID \
    --instance-type $INSTANCE_TYPE \
    --key-name $KEY_NAME \
    --security-group-ids $KAFKA_SG_ID \
    --subnet-id $PRIVATE_SUBNET_1 \
    --user-data file://infrastructure/kafka-user-data.sh \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${PROJECT_NAME}-kafka},{Key=Project,Value=${PROJECT_NAME}}]" \
    --region $REGION \
    --query 'Instances[0].InstanceId' \
    --output text)

echo "✓ EC2 instance created: $INSTANCE_ID"
echo ""
echo "Waiting for instance to be running..."

# Wait for instance to be running
aws ec2 wait instance-running \
    --instance-ids $INSTANCE_ID \
    --region $REGION

echo "✓ Instance is running"

# Get instance private IP
echo "Step 3/4: Retrieving instance details..."
PRIVATE_IP=$(aws ec2 describe-instances \
    --instance-ids $INSTANCE_ID \
    --region $REGION \
    --query 'Reservations[0].Instances[0].PrivateIpAddress' \
    --output text)

echo "✓ Private IP: $PRIVATE_IP"

echo "Step 4/4: Waiting for Kafka to start (this may take 3-5 minutes)..."
echo "  The instance is installing Docker and starting Kafka..."
sleep 180  # Wait 3 minutes for user data script to complete

# Save Kafka configuration
cat > infrastructure/kafka-config.json <<EOF
{
  "deploymentType": "ec2",
  "instanceId": "$INSTANCE_ID",
  "privateIp": "$PRIVATE_IP",
  "bootstrapServers": "$PRIVATE_IP:9092",
  "zookeeperConnect": "$PRIVATE_IP:2181",
  "topics": {
    "reservations": "flash-sale-reservations",
    "purchaseConfirmations": "flash-sale-purchase-confirmations",
    "inventoryUpdates": "flash-sale-inventory-updates",
    "expiryEvents": "flash-sale-expiry-events"
  },
  "keyPairName": "$KEY_NAME",
  "keyPairPath": "infrastructure/${KEY_NAME}.pem"
}
EOF

# Save configuration to AWS Secrets Manager
SECRET_NAME="${PROJECT_NAME}/kafka/config"

echo "Storing configuration in AWS Secrets Manager..."
aws secretsmanager create-secret \
    --name $SECRET_NAME \
    --description "Kafka configuration for Flash Sale System" \
    --secret-string "{\"bootstrapServers\":\"$PRIVATE_IP:9092\",\"zookeeperConnect\":\"$PRIVATE_IP:2181\"}" \
    --region $REGION \
    --tags "Key=Project,Value=${PROJECT_NAME}" > /dev/null 2>&1 || echo "  (Secret already exists, skipping...)"

echo "✓ Configuration stored in Secrets Manager"

echo ""
echo "========================================"
echo "✓ Kafka on EC2 Setup Completed!"
echo "========================================"
echo ""
echo "Configuration saved to: infrastructure/kafka-config.json"
echo ""
echo "Summary:"
echo "  Instance ID: $INSTANCE_ID"
echo "  Private IP: $PRIVATE_IP"
echo "  Bootstrap Servers: $PRIVATE_IP:9092"
echo "  Zookeeper: $PRIVATE_IP:2181"
echo "  SSH Key: infrastructure/${KEY_NAME}.pem"
echo ""
echo "Topics created:"
echo "  - flash-sale-reservations (10 partitions)"
echo "  - flash-sale-purchase-confirmations (10 partitions)"
echo "  - flash-sale-inventory-updates (10 partitions)"
echo "  - flash-sale-expiry-events (10 partitions)"
echo ""
echo "NOTE: Kafka is running in Docker on the EC2 instance"
echo "To SSH into the instance:"
echo "  ssh -i infrastructure/${KEY_NAME}.pem ec2-user@$PRIVATE_IP"
echo ""
echo "To view Kafka logs:"
echo "  docker logs kafka"
echo ""

# Clean up user data script
rm -f infrastructure/kafka-user-data.sh
