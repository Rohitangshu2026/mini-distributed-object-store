# Shortcuts for the commands in README.md. The Maven targets need JDK 21 as JAVA_HOME.
.PHONY: up down logs ps test e2e demo reset

# Returns once every service is healthy.
up:
	docker compose up -d --build --wait

down:
	docker compose down

logs:
	docker compose logs -f

ps:
	docker compose ps

test:
	./mvnw -B verify

# Needs a running cluster (make up).
e2e:
	./mvnw -B -Pe2e -pl e2e -am verify

# Runs demos 1 to 6 in order against a running cluster (make up); stops at the first failure.
# Each demo creates its own objects, so they also run one at a time, in any order.
demo:
	scripts/demo-1-storage.sh
	scripts/demo-2-chunking.sh
	scripts/demo-3-replication.sh
	scripts/demo-4-metadata.sh
	scripts/demo-5-failure.sh
	scripts/demo-6-integrity.sh
	@echo
	@echo "All six demos passed."

# Deletes every volume, and with it all stored objects.
reset:
	docker compose down -v
