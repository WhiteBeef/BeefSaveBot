.PHONY: build docker-build run rebuild rebuild_dev clean dev update-jar

ifeq ($(OS),Windows_NT)
	MVN_CMD = \.mvnw
else
	MVN_CMD = ./mvnw
endif

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
	$(MVN_CMD) clean
	docker-compose down -v --rmi local

update-jar: build
	if not exist target\extracted mkdir target\extracted
	java -Djarmode=layertools -jar target\BeefSaveBot-0.0.1-SNAPSHOT.jar extract --destination target\extracted
	docker cp target/extracted/application/. $(shell docker-compose ps -q telegram-bot):/app/
	docker-compose restart
	if exist target\extracted rmdir /s /q target\extracted

jdk-install:
	sudo apt-get install openjdk-17-jdk

docker-install:
	sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin docker-compose -y

start: jdk-install build docker-install docker-build run