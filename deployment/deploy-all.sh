#!/bin/bash
# deploy-all.sh
# Runs all deployment scripts in order: RabbitMQ → server-v2 → consumer
#
# Usage:
#   chmod +x deploy-all.sh
#   ./deploy-all.sh

set -e
cd "$(dirname "$0")"

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
