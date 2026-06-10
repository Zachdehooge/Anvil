#!/bin/bash
set -e

# Create config.json if it doesn't exist so Docker mounts it as a file, not a directory
if [ ! -f config.json ]; then
  echo '{}' > config.json
fi

docker build -t anvil-bot .

docker run -d \
  --name anvil \
  --restart unless-stopped \
  -v "$(pwd)/.env:/app/.env:ro" \
  -v "$(pwd)/config.json:/app/config.json" \
  anvil-bot
