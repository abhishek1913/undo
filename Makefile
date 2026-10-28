SHELL := /bin/bash
COMPOSE := docker compose -f deploy/docker-compose.yml --env-file .env
MVN := mvn -B
SEEDS ?= 100000
SEED ?= 1
THREADS ?= $(shell sysctl -n hw.ncpu 2>/dev/null || nproc)

.PHONY: build test e2e up down up-obs up-chaos demo sim sim-bugs sim-seed chaos bench logs psql fmt env

env:
	@test -f .env || cp .env.example .env

build: env
	$(MVN) -DskipTests package
	cd console && npm ci --no-audit --no-fund && npm run build
	$(COMPOSE) build

test:
	$(MVN) verify
	cd console && npm run lint && npm run typecheck && npm test

# Console end-to-end tests (Playwright) against the running stack; needs UNDO_DEMO=true in .env.
e2e:
	cd console && npx playwright test

fmt:
	$(MVN) -q spotless:apply
	cd console && npx prettier --write src e2e

up: env
	$(COMPOSE) up -d --wait

down: env
	$(COMPOSE) --profile obs --profile chaos down

# Prometheus :9090, Grafana :3001 (dashboard "Undo overview"), Jaeger :16686; tracing is on.
up-obs: env
	UNDO_TRACING=true OTEL_ENDPOINT=http://jaeger:4318/v1/traces $(COMPOSE) --profile obs up -d --wait

# 3 workers that halt at crash points, Undo reaching Shopfront through Toxiproxy (:8474).
up-chaos: env
	SHOP_BASE_URL=http://toxiproxy:18090 UNDO_CHAOS_HALT_PROBABILITY=0.0001 $(COMPOSE) --profile chaos up -d --wait --scale undo-worker=3

# 10-minute chaos test (PRD 22.5); appends a run to docs/CHAOS_RESULTS.md. CHAOS_SECONDS=600.
chaos: env
	bash chaos/run.sh

# B1-B7 (PRD 23); writes out/bench/*.json|txt, numbers go to docs/BENCHMARKS.md by hand.
bench: env
	bash bench/run.sh

logs: env
	$(COMPOSE) logs -f undo-api undo-worker

psql:
	docker exec -it undo-postgres-1 psql -U undo undo

sim:
	java -jar undo-sim/target/undo-sim.jar --seeds $(SEEDS) --threads $(THREADS) --report out/sim-report.json

sim-bugs:
	java -jar undo-sim/target/undo-sim.jar --all-bugs --seeds 20000 --threads $(THREADS)

BUG ?=
sim-seed:
	mkdir -p out && java -jar undo-sim/target/undo-sim.jar --seed $(SEED) $(if $(BUG),--bug $(BUG)) --trace out/seed-$(SEED)$(if $(BUG),-$(BUG)).log
