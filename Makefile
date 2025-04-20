.PHONY: build docker-build run rebuild rebuild_dev clean dev

build:
	mvnw clean package -DskipTests

docker-build:
	docker build -t telegram-bot:latest .

run:
	docker-compose up -d

dev:
	docker-compose -f docker-compose.dev.yml up -d

rebuild: build docker-build run

rebuild_dev: build docker-build dev

clean:
	mvnw clean
	docker-compose down -v --rmi local