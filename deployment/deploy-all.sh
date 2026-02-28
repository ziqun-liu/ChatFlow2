#!/bin/bash
# deploy-all.sh
# Runs all deployment scripts in order: RabbitMQ → server-v2 → consumer
#
# Usage:
#   chmod +x deploy-all.sh
#   ./deploy-all.sh

set -e
cd "$(dirname "$0")"

# ── Configuration (replace variable values before use) ────────────────────────
export RABBITMQ_HOST="16.147.81.157"
export RABBITMQ_USER="guest"
export RABBITMQ_PASS="guest"
export WS_URI="ws://cs6650-assignment2-lb-1697352352.us-west-2.elb.amazonaws.com/server/chat/"
# ───────────────────────────────────────────────────────────────────────────

# Ensure all scripts are executable
chmod +x rabbitmq-setup.sh deploy-server.sh consumer-setup.sh

echo "======================================"
echo " ChatFlow2 Full Deployment"
echo "======================================"

echo ""
echo "=== Step 1: RabbitMQ Setup ==="
./rabbitmq-setup.sh

echo ""
echo "=== Step 2: Server-v2 Deploy ==="
./deploy-server.sh

echo ""
echo "=== Step 3: Consumer Deploy ==="
./consumer-setup.sh

echo ""
echo "======================================"
echo " All deployments complete"
echo "======================================"
