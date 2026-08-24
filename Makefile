SHELL := /bin/bash

COMPOSE ?= docker compose
RUNTIME_PROJECT ?= fiq
DEMO_PROJECT ?= fiq-demo
RUNTIME_COMPOSE := $(COMPOSE) -p $(RUNTIME_PROJECT) -f compose.yaml
DEMO_COMPOSE := $(COMPOSE) -p $(DEMO_PROJECT) -f compose.yaml -f compose.demo.yaml

.PHONY: help build test spark-job up down demo-up demo-down ps demo-ps logs demo-logs \
	demo-test restart demo-restart clean demo-reset

help: ## Show all supported development and runtime commands.
	@printf '%s\n' \
		'FIQ commands' \
		'' \
		'  make build         Build the UI, FIQ server, and Spark maintenance job.' \
		'  make test          Run backend/domain and UI unit tests.' \
		'  make up            Start only FIQ server + PostgreSQL.' \
		'  make down          Stop the minimal FIQ runtime; preserve its database.' \
		'  make demo-up       Start the self-contained MinIO/HMS/Spark/Livy demo.' \
		'  make demo-down     Stop the demo; preserve demo data and dependency cache.' \
		'  make ps            Show minimal-runtime containers.' \
		'  make demo-ps       Show all demo containers, including successful init jobs.' \
		'  make logs          Follow FIQ server logs for the minimal runtime.' \
		'  make demo-logs     Follow FIQ, Livy, HMS, and sample initializer demo logs.' \
		'  make demo-test     Run destructive acceptance only against demo sample data.' \
		'  make restart       Restart the minimal runtime.' \
		'  make demo-restart  Restart the demo without erasing its data.' \
		'  make clean         Stop FIQ projects and clean Gradle build output only.' \
		'  make demo-reset    Erase only fiq-demo containers/volumes, ready for demo-up.' \
		'' \
		'Override RUNTIME_PROJECT or DEMO_PROJECT to create an isolated Compose project.'

build: ## Build the UI, backend, and Spark artifact.
	npm --prefix fiq-ui ci
	npm --prefix fiq-ui run build
	./gradlew :fiq-server:quarkusBuild :fiq-spark-job:shadowJar --no-daemon

test: ## Run normal backend/domain/UI tests.
	./gradlew ci --no-daemon
	npm --prefix fiq-ui ci
	npm --prefix fiq-ui test

spark-job: ## Build the Spark artifact mounted by the local demo.
	./gradlew :fiq-spark-job:shadowJar --no-daemon

up: ## Start only FIQ server and PostgreSQL.
	$(RUNTIME_COMPOSE) up --build --detach --remove-orphans

down: ## Stop the minimal runtime without deleting PostgreSQL data.
	$(RUNTIME_COMPOSE) down --remove-orphans

demo-up: spark-job ## Start the complete self-contained demo.
	$(DEMO_COMPOSE) up --build --detach --remove-orphans

demo-down: ## Stop the demo without deleting its volumes.
	$(DEMO_COMPOSE) down --remove-orphans

ps: ## Show minimal-runtime containers.
	$(RUNTIME_COMPOSE) ps --all

demo-ps: ## Show demo containers and one-shot initializer state.
	$(DEMO_COMPOSE) ps --all

logs: ## Follow minimal-runtime server logs.
	$(RUNTIME_COMPOSE) logs --follow server

demo-logs: ## Follow relevant demo service logs.
	$(DEMO_COMPOSE) logs --follow server livy hms sample-init

demo-test: ## Run Phase 1 acceptance against the running demo.
	./scripts/acceptance-core.sh

restart: down up ## Restart the minimal runtime.

demo-restart: demo-down demo-up ## Restart the demo while retaining its data.

clean: ## Stop FIQ-owned containers and clean local Gradle build output.
	$(RUNTIME_COMPOSE) down --remove-orphans
	$(DEMO_COMPOSE) down --remove-orphans
	./gradlew clean --no-daemon

demo-reset: ## Delete only the selected demo project's containers and volumes.
	$(DEMO_COMPOSE) down --volumes --remove-orphans
