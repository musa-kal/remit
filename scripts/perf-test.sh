#!/usr/bin/env bash
# NFR-05 throughput check: generates a large valid batch file, drops it on SFTP, and measures how
# long it takes until the API reports every instruction as ROUTED or REQUIRES_REVIEW. The clock
# starts right before the upload, so the time includes waiting for the next SFTP poll.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/perf-test.sh [-h] [--rows N] [--runs N] [--client ID] [--target SECONDS] [--timeout SECONDS]

Against a running stack (scripts/deploy.sh compose), for each run:
  1. scripts/gen-batch.sh <client> <rows> writes a valid file
  2. the start time is taken, then scripts/seed-sftp.sh uploads it to /inbound
  3. GET /api/v1/batches polls until the batch appears (intake-service picked it up), then
     GET /api/v1/batches/{id} until ROUTED + REQUIRES_REVIEW equals the row count
Prints the time of every run and the median, and exits 1 when the median is above the target.

The end point is what the API reports, which trails routing-service by the time api-service
needs to consume the status events, so the figure is an upper bound for "all routed".

Options:
  --rows N            rows per file (default 10000)
  --runs N            number of files, uploaded one after the other (default 1)
  --client ID         client id of the generated files (default PERF01)
  --target SECONDS    NFR-05 limit for the median (default 60)
  --timeout SECONDS   give up on a run after this long (default 300)
  -h, --help          show this help

Environment (read from .env when not set):
  REMIT_API_URL            API base URL (default http://localhost:${REMIT_API_PORT:-8080})
  REMIT_OPERATOR_PASSWORD  password of the seeded operator user
  REMIT_CLOCK_FIXED_DATE   business date pinned in intake-service; the value date of the rows
                           (compose default 2026-10-15)
EOF_USAGE
}

rows=10000
runs=1
client=PERF01
target=60
run_timeout=300
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --rows | --runs | --target | --timeout)
      [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]*$ ]] || die "$1 needs a positive number"
      case "$1" in
        --rows) rows="$2" ;;
        --runs) runs="$2" ;;
        --target) target="$2" ;;
        --timeout) run_timeout="$2" ;;
      esac
      shift
      ;;
    --client)
      [[ $# -ge 2 && "$2" =~ ^[A-Z0-9]{3,10}$ ]] || die "--client needs 3 to 10 upper-case letters or digits"
      client="$2"
      shift
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

require_cmd curl jq
load_env
API="${REMIT_API_URL:-http://localhost:${REMIT_API_PORT:-8080}}"
API="${API%/}"
[[ -n "${REMIT_OPERATOR_PASSWORD:-}" ]] || die "REMIT_OPERATOR_PASSWORD is not set (.env)"
# Compose pins the business date to 2026-10-15 unless .env says otherwise (ADR 0005).
export REMIT_CLOCK_FIXED_DATE="${REMIT_CLOCK_FIXED_DATE-2026-10-15}"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

token=""
login() {
  local body status
  body="$(jq -nc --arg p "${REMIT_OPERATOR_PASSWORD}" '{username: "operator", password: $p}')"
  status="$(curl -s -o "${work}/login" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    --data "${body}" "${API}/api/v1/auth/login")" || status=000
  [[ "${status}" == 200 ]] || die "login as operator answered ${status}; check REMIT_OPERATOR_PASSWORD"
  token="$(jq -r .accessToken "${work}/login")"
}

api_get() {
  curl -s -f --max-time 10 -H "Authorization: Bearer ${token}" "${API}$1" || printf 'null\n'
}

# seconds <ms>: formats milliseconds as seconds with one decimal.
seconds() {
  printf '%d.%d' "$(($1 / 1000))" "$((($1 % 1000) / 100))"
}

results=()
for ((run = 1; run <= runs; run++)); do
  login # tokens live 15 minutes; a fresh one per run keeps long series working
  file="$("${REMIT_ROOT}/scripts/gen-batch.sh" -o "${work}" "${client}" "${rows}")"
  name="$(basename "${file}")"
  log_info "run ${run}/${runs}: ${name} (${rows} rows)"

  t0="$(now_ms)"
  "${REMIT_ROOT}/scripts/seed-sftp.sh" "${file}" 2>/dev/null
  t_uploaded="$(now_ms)"

  batch_id=""
  t_picked=""
  done_count=0
  while :; do
    now="$(now_ms)"
    if (((now - t0) / 1000 >= run_timeout)); then
      die "run ${run} timed out after ${run_timeout}s (batch ${batch_id:-not seen}, ${done_count}/${rows} routed)"
    fi
    if [[ -z "${batch_id}" ]]; then
      batch_id="$(api_get "/api/v1/batches?clientId=${client}&size=20" |
        jq -r --arg f "${name}" 'first(.content[]? | select(.fileName == $f) | .batchId) // empty')"
      [[ -n "${batch_id}" ]] && t_picked="$(now_ms)"
    else
      detail="$(api_get "/api/v1/batches/${batch_id}")"
      done_count="$(jq -r '(.instructionsByStatus.ROUTED // 0) + (.instructionsByStatus.REQUIRES_REVIEW // 0)' <<<"${detail}")"
      if [[ "${done_count}" == "${rows}" ]]; then
        t_done="$(now_ms)"
        break
      fi
      status="$(jq -r '.status // empty' <<<"${detail}")"
      [[ "${status}" != FAILED ]] || die "batch ${batch_id} FAILED: $(jq -c . <<<"${detail}")"
    fi
    sleep 0.5
  done

  total=$((t_done - t0))
  results+=("${total}")
  log_ok "run ${run}: $(seconds "${total}")s from upload to ${rows} routed" \
    "(upload $(seconds $((t_uploaded - t0)))s, until picked up $(seconds $((t_picked - t0)))s," \
    "then $(seconds $((t_done - t_picked)))s to route)"
done

mapfile -t sorted < <(printf '%s\n' "${results[@]}" | sort -n)
count=${#sorted[@]}
if ((count % 2 == 1)); then
  median="${sorted[count / 2]}"
else
  median=$(((sorted[count / 2 - 1] + sorted[count / 2]) / 2))
fi

printf '\nNFR-05: %s rows, %d run(s)\n' "${rows}" "${count}"
for ((i = 0; i < ${#results[@]}; i++)); do
  printf '  run %d: %ss\n' "$((i + 1))" "$(seconds "${results[i]}")"
done
printf '  median: %ss (target %ss)\n' "$(seconds "${median}")" "${target}"

if ((median > target * 1000)); then
  log_error "median above the ${target}s target"
  exit 1
fi
log_ok "median within the ${target}s target"
