# Prayog developer commands. Run `make help` for the list.
.DEFAULT_GOAL := help
.PHONY: help env up down ps logs smoke e2e reset test test-java test-python test-web replay-check fmt lint build clean install

MVNW := ./mvnw -B
# The stack reads secrets from the root .env (git-ignored). `make env` creates one.
COMPOSE := docker compose --env-file .env -f deploy/compose/compose.yaml
PROFILES ?= infra app
PROFILE_FLAGS = $(foreach p,$(PROFILES),--profile $(p))

help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

install: ## Install Python and web dependencies
	uv sync
	pnpm install --frozen-lockfile

env: ## Create .env from .env.example with random local secrets (keeps an existing .env)
	@if [ -f .env ]; then echo ".env already exists; leaving it alone."; else \
		while IFS= read -r line; do \
			case "$$line" in *=change-me) echo "$${line%change-me}$$(openssl rand -hex 16)";; *) echo "$$line";; esac; \
		done < .env.example > .env; \
		echo "Created .env with random local secrets."; fi

up: ## Build changed images, start the stack, wait until healthy (PROFILES="infra app" by default)
	@test -f .env || { echo "No .env: run 'make env' first."; exit 1; }
	$(COMPOSE) $(PROFILE_FLAGS) up -d --build --remove-orphans --wait --wait-timeout 600

down: ## Stop the stack (data volumes are kept)
	$(COMPOSE) --profile '*' down

ps: ## Show stack services and their health
	$(COMPOSE) --profile '*' ps -a

logs: ## Follow stack logs (all services, or SERVICE=name)
	$(COMPOSE) --profile '*' logs -f --tail=100 $(SERVICE)

smoke: ## Check the running stack end to end (S2 acceptance)
	./deploy/compose/smoke.sh

e2e: ## Check the running market end to end: feed integrity, liquidity, a bot round trip, live journal replay
	./deploy/compose/e2e.sh

reset: ## Stop the stack and DELETE its data volumes (databases, Kafka log, Redis)
	$(COMPOSE) --profile '*' down -v

test: test-java test-python test-web ## Build and test everything

test-java: ## Java: compile, unit + integration tests, format check
	$(MVNW) verify

test-python: ## Python: lint, format check, tests
	uv run ruff check .
	uv run ruff format --check .
	uv run pytest -q

test-web: ## Web: lint, typecheck, format check, tests
	pnpm -r lint
	pnpm -r typecheck
	pnpm -r fmt:check
	pnpm -r test

replay-check: ## Record a busy session, replay it, compare event-log checksums (S8)
	$(MVNW) -pl services/exchange/exchange-core -am test -Dtest=ReplayDeterminismTest -Dsurefire.failIfNoSpecifiedTests=false

fmt: ## Format Java, Python and web code
	$(MVNW) -q spotless:apply
	uv run ruff format .
	uv run ruff check --fix .
	pnpm -r fmt

lint: ## Lint without changing files
	$(MVNW) -q spotless:check
	uv run ruff check .
	pnpm -r lint

build: ## Build Java jars and the web bundle (skips tests)
	$(MVNW) -DskipTests package
	pnpm -r build

clean: ## Remove build outputs
	$(MVNW) -q clean
	rm -rf apps/web/dist apps/web/coverage .pytest_cache .ruff_cache
