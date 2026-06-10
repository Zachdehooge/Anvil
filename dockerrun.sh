#!/bin/bash
set -e

# Create config.json if it doesn't exist so Docker mounts it as a file, not a directory
if [ ! -f config.json ]; then
  echo '{}' > config.json
fi

docker build -t anvil-bot .

# Stop and remove existing container if running
if docker ps -a --format '{{.Names}}' | grep -q '^anvil$'; then
  echo "Removing existing anvil container..."
  docker stop anvil
  docker rm anvil
fi

docker run -d \
  --name anvil \
  --restart unless-stopped \
  -v "$(pwd)/.env:/app/.env:ro" \
  -v "$(pwd)/config.json:/app/config.json" \
  anvil-bot
