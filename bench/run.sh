#!/usr/bin/env bash
# make bench: B1-B7 (PRD 23). Raw output goes to out/bench/. Needs docker (k6 runs from grafana/k6),
# Maven jars built (make build), UNDO_DEMO=true in .env. Reseeds Shopfront and empties Undo's run data.
#   B1 k6 inline overhead at 50/200/500 req/s   B2 queue drain with 1/2/4 workers
#   B3 recovery time (chaos/check.sh, from the last make chaos)   B4+B5 JMH   B6 simulator   B7 revert preview
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .env || cp .env.example .env
set -a; source .env; set +a
OUT=out/bench; mkdir -p $OUT
ONLY=${ONLY:-"b4 b5 b6 b7 b1 b2"}
export UNDO_DEMO=true UNDO_DEMO_AGENT_KEY=${UNDO_DEMO_AGENT_KEY:-undo_sk_BenchKey0000000000000000000000000000000000}
export UNDO_RATELIMIT_PER_KEY_RPS=100000 UNDO_RATELIMIT_BURST=100000   # the bench key is not what is being measured
export SHOP_SETTLEMENT_DELAY=PT10M
COMPOSE="docker compose -f deploy/docker-compose.yml --env-file .env"
API=http://localhost:8080; SHOP=http://localhost:8090
psql_undo() { docker exec -i undo-postgres-1 psql -U undo -d undo -tA -c "$1"; }
K6="docker run --rm -i --add-host host.docker.internal:host-gateway -v $PWD/bench/k6:/scripts:ro -v $PWD/$OUT:/out grafana/k6:1.2.3"
{
  echo "machine: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo), $(sysctl -n hw.ncpu 2>/dev/null || nproc) cores, $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GiB RAM, $(uname -srm)"
  echo "docker: $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')"
  echo "java: $(java -version 2>&1 | head -1) · commit $(git rev-parse --short HEAD) · $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} | tee $OUT/env.txt

fresh_stack() {  # $1 = number of workers
  $COMPOSE up -d --wait --force-recreate --scale undo-worker="$1" postgres shopfront undo-api undo-worker >/dev/null
  psql_undo "truncate events, action_transitions, postings, journal_entries, attempts, approvals, revert_items, revert_plans, recon_findings, recon_runs, webhook_events, llm_turns, actions, tasks, runs cascade" >/dev/null
  curl -fsS -X POST $SHOP/admin/seed -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"seed":42,"customers":200,"orders":3000,"tickets":200}' >/dev/null
  curl -fsS -X POST $SHOP/admin/faults -H "X-Admin-Token: $SHOP_ADMIN_TOKEN" -d '{"preset":"calm"}' >/dev/null
}

for b in $ONLY; do case $b in
b4|b5)
  if [ "$b" = b4 ]; then
    echo "== B4 + B5: JMH (2 forks, 3 warmup x 2 s, 5 measured x 3 s)"
    java -jar bench/jmh/target/undo-bench-jmh.jar -rf json -rff $OUT/jmh.json 2>&1 | tee $OUT/jmh.txt | tail -6
  fi ;;
b6)
  echo "== B6: simulator throughput at 8 threads (100,000 seeds)"
  /usr/bin/time -p java -jar undo-sim/target/undo-sim.jar --seeds 100000 --threads 8 2>&1 | tee $OUT/b6.txt | tail -12 ;;
b7)
  echo "== B7: revert preview of a run with 500 SUCCEEDED actions"
  mvn -B -q -Dtest=RevertPreviewBenchIT -Dsurefire.failIfNoSpecifiedTests=false verify -pl undo-server -am 2>&1 | grep -E "B7 revert|ERROR" | head -3
  cp undo-server/target/bench-b7.txt $OUT/b7.txt; cat $OUT/b7.txt ;;
b1)
  echo "== B1: inline overhead (k6), Shopfront calm, 1 worker"
  fresh_stack 1
  $K6 run -e API=http://host.docker.internal:8080 -e KEY=$UNDO_DEMO_AGENT_KEY --summary-export /out/b1-summary.json /scripts/propose.js > $OUT/b1.txt 2>&1 || true
  grep -E "undo_overhead_ms|provider_ms|http_req_duration|http_req_failed|not_succeeded|dropped_iterations" $OUT/b1.txt | grep -v "^ *[a-z_]*{scenario" || true
  echo "   actions per run and status (inline = completed within the request):"
  psql_undo "select split_part(r.title, ' #', 1), a.status, count(*) from actions a join runs r on r.id = a.run_id where r.title like 'B1 %' group by split_part(r.title, ' #', 1), 2 order by 1, 2" | sed 's/^/   /' | tee $OUT/b1-status.txt ;;
b2)
  echo "== B2: queue drain, 5,000 approved actions, 1/2/4 workers"
  printf "workers\tsucceeded\tdrain_s\tactions_per_s\n" > $OUT/b2.tsv
  for W in 1 2 4; do
    fresh_stack $W
    $K6 run -e API=http://host.docker.internal:8080 -e KEY=$UNDO_DEMO_AGENT_KEY -e N=5000 -e WORKERS=$W /scripts/queue.js 2>&1 | grep -E "iterations|checks" | head -2
    N=$(psql_undo "select count(*) from actions where status = 'AWAITING_APPROVAL'")
    echo "   $W workers: $N actions awaiting approval"
    # Approve everything while no worker runs, then start W workers: the drain is measured alone.
    $COMPOSE stop undo-worker >/dev/null 2>&1
    JAR=$(mktemp); CSRF=$(curl -fsS -c "$JAR" -X POST $API/api/auth/login -H 'Content-Type: application/json' \
      -d '{"email":"approver@undo.local","password":"undo-demo-approver"}' | sed -E 's/.*"csrf_token":"([^"]+)".*/\1/')
    for off in $(seq 0 500 $((N - 1))); do
      BODY=$(psql_undo "select json_build_object('decision','approve','items',coalesce(json_agg(json_build_object('approval_id',id,'args_hash',args_hash)),'[]'::json)) from (select id, args_hash from approvals where status = 'PENDING' order by requested_at limit 500) q")
      curl -fsS -b "$JAR" -X POST $API/api/approvals/bulk -H "X-CSRF-Token: $CSRF" -H 'Content-Type: application/json' -d "$BODY" >/dev/null
    done
    echo "   approved with no worker running: $(psql_undo "select count(*) from actions where status = 'APPROVED'") APPROVED, $(psql_undo "select count(*) from actions where status = 'SUCCEEDED'") already SUCCEEDED"
    $COMPOSE up -d --wait --scale undo-worker="$W" undo-worker >/dev/null 2>&1
    for _ in $(seq 1 1200); do
      LEFT=$(psql_undo "select count(*) from actions where status in ('APPROVED','EXECUTING','RETRY_WAIT','UNKNOWN','AWAITING_APPROVAL')")
      [ "$LEFT" = "0" ] && break; sleep 0.25
    done
    OK=$(psql_undo "select count(*) from actions where status = 'SUCCEEDED'")
    # drain = first execution start to last completion, from the actions' own timestamps
    SPAN=$(psql_undo "select round(extract(epoch from max(completed_at) - min(first_attempt_at))::numeric, 2) from actions where status = 'SUCCEEDED'")
    printf '%s\t%s\t%s\t%s\n' "$W" "$OK" "$SPAN" "$(echo "scale=0; $OK / $SPAN" | bc)" | tee -a $OUT/b2.tsv
    rm -f "$JAR"
  done ;;
esac; done
echo "== done; raw results in $OUT"
