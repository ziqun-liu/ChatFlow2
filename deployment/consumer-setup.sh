#!/bin/bash
# consumer-setup.sh
# Builds consumer JAR and deploys to the consumer EC2 instance.
#
# Usage:
#   chmod +x consumer-setup.sh
#   ./consumer-setup.sh
#
# Prerequisites on consumer EC2:
#   - Java 11+ installed
#   - ec2-user has write access to /opt/consumer

set -e
# Variables: SSH_KEY, CONSUMER_HOST, SERVER_URLS, RABBITMQ_HOST
# ── Configuration (assign variables before use) ────────────────────────
SSH_KEY="~/.ssh/cs6650-assignment2.pem"          # Replace with your SSH private key path, e.g. ~/.ssh/cs6650.pem
EC2_USER="ec2-user"
CONSUMER_HOST="54.212.1.204"   # Replace with public IP of consumer EC2 instance

# Private IPs of all server-v2 instances, comma-separated (use private IPs to reduce latency)
# Find them in AWS Console: EC2 → Private IPv4 addresses
SERVER_URLS="http://172.31.22.27:8080/server,http://172.31.20.201:8080/server,http://172.31.28.59:8080/server,http://172.31.28.143:8080/server"

RABBITMQ_HOST="172.31.28.111"  # Replace with private IP of RabbitMQ EC2 (not public IP)
CONSUMER_THREADS="4"                           # Number of consumer threads (20 rooms / 4 threads = 5 rooms per thread)

CONSUMER_DIR="$(dirname "$0")/../consumer"
JAR_NAME="consumer-1.0-SNAPSHOT.jar"
LOCAL_JAR="$CONSUMER_DIR/target/$JAR_NAME"
REMOTE_DIR="/opt/consumer"
# ───────────────────────────────────────────────────────────────────────────

echo "======================================"
echo " Step 1: Build consumer JAR"
echo "======================================"
cd "$CONSUMER_DIR"
mvn clean package -q
echo "Build complete: $LOCAL_JAR"

echo ""
echo "======================================"
echo " Step 2: Deploy to consumer EC2 ($CONSUMER_HOST)"
echo "======================================"

echo "  Uploading $JAR_NAME..."
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$CONSUMER_HOST" \
  "sudo mkdir -p $REMOTE_DIR && sudo chown $EC2_USER:$EC2_USER $REMOTE_DIR"

scp -i "$SSH_KEY" -o StrictHostKeyChecking=no \
  "$LOCAL_JAR" \
  "$EC2_USER@$CONSUMER_HOST:$REMOTE_DIR/$JAR_NAME"

echo ""
echo "======================================"
echo " Step 3: Start consumer on EC2"
echo "======================================"

ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "$EC2_USER@$CONSUMER_HOST" bash <<EOF
  set -e

  # Kill any existing consumer process
  echo "  Stopping existing consumer (if any)..."
  pkill -f "$JAR_NAME" 2>/dev/null || true
  sleep 2

  # Start consumer in background, redirect logs to file
  echo "  Starting consumer..."
  nohup env \
    RABBITMQ_HOST="$RABBITMQ_HOST" \
    RABBITMQ_PORT="5672" \
    RABBITMQ_USER="guest" \
    RABBITMQ_PASS="guest" \
    SERVER_URLS="$SERVER_URLS" \
    CONSUMER_THREADS="$CONSUMER_THREADS" \
    java -jar "$REMOTE_DIR/$JAR_NAME" \
    > "$REMOTE_DIR/consumer.log" 2>&1 &

  echo "  Consumer PID: \$!"
  echo \$! > "$REMOTE_DIR/consumer.pid"

  # Wait and verify process is still running
  sleep 3
  if kill -0 \$(cat "$REMOTE_DIR/consumer.pid") 2>/dev/null; then
    echo "  Consumer started successfully."
    echo "  Logs: $REMOTE_DIR/consumer.log"
  else
    echo "  Consumer failed to start. Last log lines:"
    tail -20 "$REMOTE_DIR/consumer.log"
    exit 1
  fi
EOF

echo ""
echo "======================================"
echo " Deployment complete"
echo "======================================"
echo "  Consumer running on: $CONSUMER_HOST"
echo "  Logs: ssh -i $SSH_KEY $EC2_USER@$CONSUMER_HOST 'tail -f $REMOTE_DIR/consumer.log'"
echo "  Stop: ssh -i $SSH_KEY $EC2_USER@$CONSUMER_HOST 'pkill -f $JAR_NAME'"
