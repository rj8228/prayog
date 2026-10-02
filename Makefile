# Prayog developer commands. Run `make help` for the list.
.DEFAULT_GOAL := help
.PHONY: help up down test test-java test-python test-web fmt lint build logs clean install

MVNW := ./mvnw -B

help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

install: ## Install Python and web dependencies
	uv sync
	pnpm install --frozen-lockfile

up: ## Start the local stack (Docker Compose arrives in S2)
	@echo "make up: Docker Compose stack arrives in Session S2."

down: ## Stop the local stack (Docker Compose arrives in S2)
	@echo "make down: Docker Compose stack arrives in Session S2."

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

logs: ## Tail service logs (Docker Compose arrives in S2)
	@echo "make logs: Docker Compose stack arrives in Session S2."

clean: ## Remove build outputs
	$(MVNW) -q clean
	rm -rf apps/web/dist apps/web/coverage .pytest_cache .ruff_cache
