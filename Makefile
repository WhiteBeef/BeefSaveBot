.PHONY: build docker-build run rebuild rebuild_dev clean dev update-jar jdk-install docker-install start


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

build:
	$(MVN_CMD) clean package -DskipTests

docker-build:
	docker build -t telegram-bot:latest .

run:
	docker-compose up -d

dev:
	docker-compose -f docker-compose.dev.yml up -d

rebuild: build docker-build run

rebuild_dev: build docker-build dev

clean:
	$MVN_CMD clean
	docker-compose down -v --rmi local
	-$(RMDIR) target

update-jar: build
	@echo "Extracting JAR layers..."
	-$(RMDIR) target$(FILE_SEPARATOR)extracted
	$(MKDIR) target$(FILE_SEPARATOR)extracted
	java -Djarmode=layertools -jar target$(FILE_SEPARATOR)BeefSaveBot-0.0.1-SNAPSHOT.jar extract --destination target$(FILE_SEPARATOR)extracted
	@echo "Copying application layer to container..."
	docker cp target/extracted/application/. $(shell docker-compose ps -q telegram-bot):/app/
	@echo "Restarting docker-compose..."
	docker-compose restart
	-$(RMDIR) target$(FILE_SEPARATOR)extracted

jdk-install:
	@echo "Installing OpenJDK 21 (Ubuntu/Debian only)..."
	sudo apt-get update && sudo apt-get install -y openjdk-21-jdk

docker-install:
	@echo "Installing Docker (Ubuntu/Debian only)..."
	sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-compose

start: jdk-install build docker-install docker-build run