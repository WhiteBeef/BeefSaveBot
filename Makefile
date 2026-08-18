.PHONY: build docker-build up down logs deploy dev clean jdk-install docker-install start

SEP = \ /

ifeq ($(OS),Windows_NT)
FILE_SEPARATOR := $(word 1,$(SEP))
RM = del /Q /F
RMDIR = rmdir /S /Q
MKDIR = mkdir
else
FILE_SEPARATOR = $(word 2,$(SEP))
RM = rm -f
RMDIR = rm -rf
MKDIR = mkdir -p
endif

MVN_CMD = .$(FILE_SEPARATOR)mvnw

# Собрать jar (application/libs слои для Dockerfile)
build:
	$(MVN_CMD) clean package -DskipTests

# Пересобрать docker-образ, который реально использует docker compose
# (без --pull, чтобы каждый раз не тянуть заново базовый образ; кэш слоёв
# всё равно инвалидируется, когда меняется jar или сам Dockerfile)
docker-build:
	docker compose build telegram-bot

# Поднять/пересоздать контейнер на актуальном образе
up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f telegram-bot

# Единая команда: собрать jar -> пересобрать образ (jar + либы, напр. yt-dlp) -> перезапустить контейнер
deploy: build docker-build up

dev:
	docker compose -f docker-compose.dev.yml up -d --build

clean:
	$(MVN_CMD) clean
	docker compose down -v --rmi local
	-$(RMDIR) target

jdk-install:
	@echo "Installing OpenJDK 21 (Ubuntu/Debian only)..."
	sudo apt-get update && sudo apt-get install -y openjdk-21-jdk

docker-install:
	@echo "Installing Docker (Ubuntu/Debian only)..."
	sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

start: jdk-install docker-install deploy
