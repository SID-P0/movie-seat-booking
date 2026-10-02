.PHONY: build up down logs burst clean

# Build all services
build:
	docker compose build

# Start all services
up:
	docker compose up -d --build

# Stop all services
down:
	docker compose down

# View logs
logs:
	docker compose logs -f

# Run burst test
burst:
	./burst.sh http://localhost:8080

# Run burst test with custom params
burst-heavy:
	./burst.sh http://localhost:8080 500 20000

# Clean everything
clean:
	docker compose down -v
	./gradlew clean

# Local build (without Docker)
local-build:
	./gradlew clean build -x test

# Health check
health:
	@echo "Liveness:"
	@curl -sf http://localhost:8080/health/live | python3 -m json.tool
	@echo "\nReadiness:"
	@curl -sf http://localhost:8080/health/ready | python3 -m json.tool
