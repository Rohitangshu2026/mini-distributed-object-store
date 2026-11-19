# Shortcuts for the commands in README.md. The Maven targets need JDK 21 as JAVA_HOME.
.PHONY: up down logs ps test e2e reset

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

# Deletes every volume, and with it all stored objects.
reset:
	docker compose down -v
