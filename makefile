# Makefile for Knowledge Base App
.PHONY: help build-multiarch build-local run stop clean restart rebuild logs db-shell test

# Определяем архитектуру системы
ARCH := $(shell uname -m)
ifeq ($(ARCH),arm64)
    PLATFORM := linux/arm64
else ifeq ($(ARCH),aarch64)
    PLATFORM := linux/arm64
else
    PLATFORM := linux/amd64
endif

# Переменные
DOCKER_COMPOSE = docker-compose
APP_NAME = knowledge-base-app
GRADLE = ./gradlew

# Default target - показать помощь
help: ## Показать это сообщение помощи
	@echo "Доступные команды:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ============= СБОРКА =============

build-local: ## Сборка для текущей платформы ($(PLATFORM))
	@echo "Building for $(PLATFORM)"
	docker buildx build --platform $(PLATFORM) -t $(APP_NAME):latest --load .

build-multiarch: ## Сборка для всех платформ
	docker buildx build --platform linux/amd64,linux/arm64 -t $(APP_NAME):multiarch .

build: build-local ## Алиас для build-local

# ============= ЗАПУСК И ОСТАНОВКА =============

run: ## Запуск всех сервисов
	$(DOCKER_COMPOSE) up -d

stop: ## Остановка всех сервисов
	$(DOCKER_COMPOSE) down

up: build-local run ## Сборка и запуск

down: stop ## Алиас для stop

# ============= ПЕРЕЗАПУСК (ГЛАВНАЯ КОМАНДА) =============

restart: ## Быстрый рестарт приложения (без пересборки Docker)
	$(GRADLE) build
	$(DOCKER_COMPOSE) restart app
	@echo "✅ App restarted"
	@sleep 2
	$(MAKE) logs

rebuild: ## Пересборка JAR и Docker образа, затем рестарт
	$(GRADLE) clean build
	$(DOCKER_COMPOSE) stop app
	$(DOCKER_COMPOSE) build app
	$(DOCKER_COMPOSE) up -d app
	@echo "✅ App rebuilt and restarted"
	$(MAKE) logs

rebuild-full: ## Полная пересборка всего проекта
	$(GRADLE) clean build
	$(DOCKER_COMPOSE) down
	$(MAKE) build-local
	$(DOCKER_COMPOSE) up -d
	@echo "✅ Full rebuild complete"
	$(MAKE) logs

# ============= ЛОГИ =============

logs: ## Показать логи приложения (follow mode)
	$(DOCKER_COMPOSE) logs -f app

logs-all: ## Показать все логи
	$(DOCKER_COMPOSE) logs -f

logs-db: ## Показать логи БД
	$(DOCKER_COMPOSE) logs -f postgres

# ============= БАЗА ДАННЫХ =============

db-shell: ## Подключиться к PostgreSQL
	$(DOCKER_COMPOSE) exec postgres psql -U postgres -d knowledge_base

db-backup: ## Создать backup БД
	@mkdir -p backups
	$(DOCKER_COMPOSE) exec postgres pg_dump -U postgres knowledge_base > backups/backup_$$(date +%Y%m%d_%H%M%S).sql
	@echo "✅ Database backed up"

db-reset: ## Сбросить БД (осторожно!)
	$(DOCKER_COMPOSE) exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS knowledge_base;"
	$(DOCKER_COMPOSE) exec postgres psql -U postgres -c "CREATE DATABASE knowledge_base;"
	@echo "✅ Database reset"

# ============= ТЕСТИРОВАНИЕ =============

test: ## Запустить тесты
	$(GRADLE) test

test-api: ## Тест создания заметки
	@curl -X POST http://localhost:8080/api/notes/quick \
		-H "Content-Type: application/json" \
		-d '{"userId": 1, "content": "Test note from Makefile"}' \
		| python3 -m json.tool 2>/dev/null || echo "Response received"

test-embedding: ## Тест embedding сервиса
	@curl -X POST http://localhost:8080/api/notes/embeddings/generate \
		-H "Content-Type: application/json" \
		-d '{"userId": 1}' \
		| python3 -m json.tool 2>/dev/null || echo "Response received"

# ============= ОЧИСТКА =============

clean: ## Полная очистка (контейнеры, volumes, образы)
	$(DOCKER_COMPOSE) down -v
	docker rmi $(APP_NAME):latest 2>/dev/null || true
	$(GRADLE) clean
	@echo "✅ Cleanup complete"

clean-logs: ## Очистить логи контейнеров
	@docker ps -q | xargs -r docker inspect --format='{{.LogPath}}' | xargs -r truncate -s 0 2>/dev/null || true
	@echo "✅ Logs cleaned"

# ============= МОНИТОРИНГ =============

status: ## Статус сервисов
	$(DOCKER_COMPOSE) ps

health: ## Проверка health
	@curl -s http://localhost:8080/health > /dev/null 2>&1 && echo "✅ App: OK" || echo "❌ App: DOWN"
	@$(DOCKER_COMPOSE) exec postgres pg_isready > /dev/null 2>&1 && echo "✅ DB: OK" || echo "❌ DB: DOWN"

# ============= РАЗРАБОТКА =============

shell: ## Войти в контейнер приложения
	$(DOCKER_COMPOSE) exec app /bin/bash

watch: ## Запуск с автоперезагрузкой (если настроено)
	$(GRADLE) run --continuous

env-check: ## Проверка переменных окружения
	@echo "OPENAI_API_KEY: $${OPENAI_API_KEY:+SET}"
	@echo "DATABASE_URL: $${DATABASE_URL:-jdbc:postgresql://localhost:5432/knowledge_base}"
	@echo "PLATFORM: $(PLATFORM)"

# ============= БЫСТРЫЕ АЛИАСЫ =============

r: restart       ## r - быстрый рестарт
rb: rebuild      ## rb - пересборка и рестарт
l: logs         ## l - логи
s: status       ## s - статус
d: down         ## d - остановка
c: clean        ## c - очистка

# ============= СОСТАВНЫЕ КОМАНДЫ =============

fresh: clean up ## Чистый старт с нуля
	@echo "✅ Fresh start complete"

dev: ## Режим разработки - рестарт при изменениях
	@echo "Watching for changes..."
	@while true; do \
		inotifywait -r -e modify,create,delete src/; \
		$(MAKE) restart; \
	done