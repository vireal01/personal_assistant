# Makefile
.PHONY: build-multiarch build-local run stop clean

# Определяем архитектуру системы
ARCH := $(shell uname -m)
ifeq ($(ARCH),arm64)
    PLATFORM := linux/arm64
else ifeq ($(ARCH),aarch64)
    PLATFORM := linux/arm64
else
    PLATFORM := linux/amd64
endif

# Сборка для текущей платформы
build-local:
	@echo "Building for $(PLATFORM)"
	docker buildx build --platform $(PLATFORM) -t knowledge-base-app:latest --load .

# Сборка для всех платформ
build-multiarch:
	docker buildx build --platform linux/amd64,linux/arm64 -t knowledge-base-app:multiarch .

# Запуск
run:
	docker compose up -d

# Остановка
stop:
	docker compose down

# Очистка
clean:
	docker compose down -v
	docker rmi knowledge-base-app:latest

# Сборка и запуск
up: build-local run

# Логи
logs:
	docker compose logs -f

restart:
	./gradlew build && docker-compose restart app