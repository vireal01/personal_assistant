#!/bin/bash
# build.sh

# Определяем архитектуру
ARCH=$(uname -m)
PLATFORM="linux/amd64"

if [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
    PLATFORM="linux/arm64"
fi

echo "Detected architecture: $ARCH"
echo "Building for platform: $PLATFORM"

# Собираем образ
docker buildx build \
  --platform $PLATFORM \
  -t knowledge-base-app:latest \
  --load \
  .

# Запускаем
docker compose up -d

echo "Application started successfully!"