.PHONY: build run rebuild dev clean

build:
	mvnw clean package -DskipTests
	docker-compose build telegram-bot

run:
	docker-compose up -d

rebuild: build run

dev: build
	docker-compose -f docker-compose.dev.yml up -d

clean:
	docker-compose down -v --rmi all
