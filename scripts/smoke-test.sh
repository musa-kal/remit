#!/usr/bin/env bash
# End-to-end smoke test (spec 10.3). Brings up the compose stack (or uses the local Kubernetes
# deployment), uploads the five sample files, waits until the API has settled, and compares what
# the API reports with docs/e2e-expectations.md. Then runs one dead-letter cycle with a FORCE_FAIL
# instruction. Prints a PASS/FAIL table and exits non-zero on any mismatch. Needs bash, curl and
# jq, plus the docker CLI for compose or kubectl for k8s.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/smoke-test.sh [-h] [--target compose|k8s] [--no-up] [--reset] [--keep]
                             [--timeout SECONDS]

Steps:
  1. scripts/deploy.sh compose (skipped with --no-up or --target k8s) and a check that the stack
     holds no batches
  2. scripts/seed-sftp.sh with every samples/*.csv
  3. poll GET /api/v1/stats/summary until the five files have settled
  4. assert the summary, every batch, the rejected rows, the duplicate instruction, account
     masking, the audit trail, role checks and where the files ended on SFTP
  5. dead-letter cycle: a one-row FORCE_FAIL file must become a PENDING dead letter; an admin
     replay answers 202, an immediate second replay 409, and the dead letter returns to PENDING
     with replayCount 1
  6. docker compose down -v, unless --keep, --no-up or --target k8s

Options:
  --target compose   (default) the docker compose stack
  --target k8s       the local kind deployment of scripts/k8s-up.sh as it is (never deployed or
                     torn down here): API at http://localhost:8080 (node port), samples uploaded
                     and SFTP directories checked with kubectl exec (REMIT_SEED_VIA=kubectl).
                     It needs empty databases: scripts/k8s-down.sh --keep-cluster, then
                     scripts/k8s-up.sh, resets them
  --no-up            use the running compose stack as it is (for example REMIT_API_URL pointing
                     elsewhere); it is never torn down
  --reset            docker compose down -v first, so the run starts from empty volumes
  --keep             leave the stack running afterwards
  --timeout SECONDS  how long to wait for the samples to settle (default 180)
  -h, --help         show this help

Environment (read from .env when not set):
  REMIT_API_URL            API base URL (default http://localhost:${REMIT_API_PORT:-8080})
  REMIT_OPERATOR_PASSWORD  password of the seeded operator user
  REMIT_ADMIN_PASSWORD     password of the seeded admin user
  REMIT_SEED_VIA           seed-sftp.sh transport: compose (default), kubectl (default for
                           --target k8s) or sftp
  REMIT_KUBE_CONTEXT       kubectl context for --target k8s (default kind-remit)

The samples are only accepted once per stack (duplicate file detection), so the stack must be
empty: start with --reset, or run scripts/deploy.sh compose on fresh volumes first.
EOF_USAGE
}

target=compose
up=true
reset=false
keep=false
settle_timeout=180
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --target)
      [[ $# -ge 2 && ("$2" == compose || "$2" == k8s) ]] || die "--target needs compose or k8s"
      target="$2"
      shift
      ;;
    --no-up) up=false ;;
    --reset) reset=true ;;
    --keep) keep=true ;;
    --timeout)
      [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || die "--timeout needs a number of seconds"
      settle_timeout="$2"
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
reset_hint="rerun with --reset"
if [[ "${target}" == k8s ]]; then
  require_cmd kubectl
  [[ "${reset}" == false ]] ||
    die "--reset is for compose; for k8s run scripts/k8s-down.sh --keep-cluster, then scripts/k8s-up.sh"
  up=false
  # The kind config publishes node port 30080 as localhost:8080.
  export REMIT_API_URL="${REMIT_API_URL:-http://localhost:8080}"
  export REMIT_SEED_VIA="${REMIT_SEED_VIA:-kubectl}"
  reset_hint="reset it with scripts/k8s-down.sh --keep-cluster, then scripts/k8s-up.sh"
fi
API="${REMIT_API_URL:-http://localhost:${REMIT_API_PORT:-8080}}"
API="${API%/}"
[[ -n "${REMIT_OPERATOR_PASSWORD:-}" ]] || die "REMIT_OPERATOR_PASSWORD is not set (.env)"
[[ -n "${REMIT_ADMIN_PASSWORD:-}" ]] || die "REMIT_ADMIN_PASSWORD is not set (.env)"
if [[ "${up}" == false && "${reset}" == true ]]; then
  die "--reset needs the compose stack; drop --no-up"
fi

work="$(mktemp -d)"
cleanup() {
  local code=$?
  rm -rf "${work}"
  if [[ "${up}" == true && "${keep}" == false ]]; then
    log_info "tearing down: docker compose down -v (use --keep to leave the stack running)"
    compose down -v >/dev/null 2>&1 || true
  fi
  exit "${code}"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------
# HTTP and assertion helpers
# ---------------------------------------------------------------------------------------------

HTTP_STATUS=""
HTTP_BODY=""

# http <method> <path> [token] [json body]: sets HTTP_STATUS and HTTP_BODY.
http() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}"
  local args=(-s -o "${work}/body" -w '%{http_code}' -X "${method}" --max-time 20)
  if [[ -n "${token}" ]]; then
    args+=(-H "Authorization: Bearer ${token}")
  fi
  if [[ -n "${data}" ]]; then
    args+=(-H 'Content-Type: application/json' --data "${data}")
  fi
  HTTP_STATUS="$(curl "${args[@]}" "${API}${path}")" || HTTP_STATUS="000"
  HTTP_BODY="$(cat "${work}/body" 2>/dev/null || true)"
}

# get <path> <token>: prints the body of a GET, or null when it does not answer 200.
get() {
  http GET "$1" "$2"
  if [[ "${HTTP_STATUS}" != 200 ]]; then
    log_warn "GET $1 answered ${HTTP_STATUS}: ${HTTP_BODY:0:200}"
    printf 'null\n'
    return 0
  fi
  printf '%s\n' "${HTTP_BODY}"
}

# login <username> <password>: prints an access token. Exits on the first failure, because five
# failures lock the username for 10 minutes.
login() {
  local body
  body="$(jq -nc --arg u "$1" --arg p "$2" '{username: $u, password: $p}')"
  http POST /api/v1/auth/login "" "${body}"
  [[ "${HTTP_STATUS}" == 200 ]] ||
    die "login as $1 answered ${HTTP_STATUS}; check its password in .env (5 failures lock the user for 10 minutes)"
  jq -r .accessToken <<<"${HTTP_BODY}"
}

RESULTS=()
FAILURES=0

record() {
  local verdict="$1" name="$2" expected="$3" actual="$4"
  RESULTS+=("${verdict}"$'\t'"${name}"$'\t'"${expected}"$'\t'"${actual}")
  if [[ "${verdict}" == FAIL ]]; then
    FAILURES=$((FAILURES + 1))
    log_warn "FAIL ${name}: expected ${expected}, got ${actual}"
  fi
}

# check <name> <expected> <actual>: plain string comparison.
check() {
  if [[ "$2" == "$3" ]]; then
    record PASS "$1" "$2" "$3"
  else
    record FAIL "$1" "$2" "$3"
  fi
}

# check_json <name> <json> <jq filter> <expected JSON>: compares as JSON values, so 54300.00
# equals 54300 and key order does not matter.
check_json() {
  local name="$1" json="$2" filter="$3" expected="$4" actual
  actual="$(jq -c "${filter}" <<<"${json}" 2>/dev/null)" || actual="<no value>"
  if jq -e --argjson e "${expected}" "(${filter}) == \$e" <<<"${json}" >/dev/null 2>&1; then
    record PASS "${name}" "${expected}" "${actual}"
  else
    record FAIL "${name}" "${expected}" "${actual}"
  fi
}

# shorten <text>: cuts long JSON values so the table stays readable; a FAIL is also logged in full.
shorten() {
  if ((${#1} > 44)); then
    printf '%s...\n' "${1:0:41}"
  else
    printf '%s\n' "$1"
  fi
}

print_results() {
  local row verdict name expected actual color
  printf '\n%-6s  %-52s  %-44s  %s\n' RESULT CHECK EXPECTED ACTUAL
  printf '%-6s  %-52s  %-44s  %s\n' ------ ----- -------- ------
  for row in "${RESULTS[@]}"; do
    IFS=$'\t' read -r verdict name expected actual <<<"${row}"
    color="${_c_green}"
    [[ "${verdict}" == PASS ]] || color="${_c_red}"
    printf '%s%-6s%s  %-52s  %-44s  %s\n' "${color}" "${verdict}" "${_c_reset}" "${name}" \
      "$(shorten "${expected}")" "$(shorten "${actual}")"
  done
  printf '\n%d checks, %d passed, %d failed\n' "${#RESULTS[@]}" "$((${#RESULTS[@]} - FAILURES))" "${FAILURES}"
}

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | cut -d' ' -f1
  else
    printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1
  fi
}

seed() {
  local args=()
  if [[ -n "${REMIT_SEED_VIA:-}" ]]; then
    args+=(--via "${REMIT_SEED_VIA}")
  fi
  "${REMIT_ROOT}/scripts/seed-sftp.sh" ${args[@]+"${args[@]}"} "$@"
}

# sftp_available: whether the SFTP server of the target (compose container or k8s pod) is up.
sftp_available() {
  if [[ "${target}" == k8s ]]; then
    [[ "$(kube get deployment sftp -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)" == 1 ]]
  else
    command -v docker >/dev/null 2>&1 && [[ "$(service_state sftp)" == "running healthy" ]]
  fi
}

# sftp_sh <script> [arg...]: runs sh -c <script> with the arguments in the SFTP server.
sftp_sh() {
  local script="$1"
  shift
  if [[ "${target}" == k8s ]]; then
    kube exec deployment/sftp -c sftp -- sh -c "${script}" sh "$@"
  else
    compose exec -T sftp sh -c "${script}" sh "$@"
  fi
}

# sftp_listing <dir>: the file names in a directory of the SFTP server, space separated.
sftp_listing() {
  # shellcheck disable=SC2016 # expanded by the container's sh, not here
  sftp_sh 'ls -1 "$1" | sort | tr "\n" " "' "$1"
}

# ---------------------------------------------------------------------------------------------
# 1. Stack
# ---------------------------------------------------------------------------------------------

if [[ "${up}" == true ]]; then
  require_cmd docker
  if [[ "${reset}" == true ]]; then
    log_info "--reset: docker compose down -v"
    compose down -v >/dev/null 2>&1
  fi
  "${REMIT_ROOT}/scripts/deploy.sh" compose
fi

log_info "API: ${API}"
OPERATOR_TOKEN="$(login operator "${REMIT_OPERATOR_PASSWORD}")"
ADMIN_TOKEN="$(login admin "${REMIT_ADMIN_PASSWORD}")"

summary="$(get /api/v1/stats/summary "${OPERATOR_TOKEN}")"
existing="$(jq '[.batches[]] | add' <<<"${summary}")"
[[ "${existing}" == 0 ]] ||
  die "the stack already holds ${existing} batches; the samples need an empty stack (${reset_hint})"
# Files seeded a moment ago are not batches yet; they would turn the samples into duplicates.
if sftp_available && [[ -n "$(sftp_listing "/home/${REMIT_SFTP_USER:-remit}/inbound")" ]]; then
  die "SFTP /inbound already holds files waiting for the poller; ${reset_hint}"
fi

# ---------------------------------------------------------------------------------------------
# 2 and 3. Seed the samples and wait until they have settled
# ---------------------------------------------------------------------------------------------

seed
log_info "waiting up to ${settle_timeout}s for the samples to settle (the poller runs every REMIT_SFTP_POLL_INTERVAL)"
settle_filter='([.batches[]] | add) == 5 and .batches.RECEIVED == 0
  and (.instructions.ROUTED + .instructions.REQUIRES_REVIEW) == 30
  and .duplicateDeliveries >= 1 and .rejectedRows >= 5'
start="$(date +%s)"
settled=false
while (($(date +%s) - start < settle_timeout)); do
  summary="$(get /api/v1/stats/summary "${OPERATOR_TOKEN}")"
  if jq -e "${settle_filter}" <<<"${summary}" >/dev/null 2>&1; then
    settled=true
    break
  fi
  sleep 3
done
elapsed=$(($(date +%s) - start))
# Give straggling events a moment; anything extra then shows up as a mismatch below.
sleep 2
summary="$(get /api/v1/stats/summary "${OPERATOR_TOKEN}")"
if [[ "${settled}" == true ]]; then
  log_ok "samples settled ${elapsed}s after the upload"
  record PASS "samples settled within ${settle_timeout}s" "settled" "${elapsed}s"
else
  record FAIL "samples settled within ${settle_timeout}s" "settled" "timed out"
fi

# ---------------------------------------------------------------------------------------------
# 4. Assertions from docs/e2e-expectations.md
# ---------------------------------------------------------------------------------------------

check_json "summary batches" "${summary}" .batches \
  '{"RECEIVED":0,"COMPLETED":2,"COMPLETED_WITH_ERRORS":1,"FAILED":2}'
check_json "summary instructions" "${summary}" .instructions \
  '{"ROUTED":28,"REQUIRES_REVIEW":2,"DUPLICATE":0,"DEAD_LETTERED":0,"REPLAYED":0}'
check_json "summary duplicateDeliveries" "${summary}" .duplicateDeliveries 1
check_json "summary rejectedRows" "${summary}" .rejectedRows 5
check_json "summary deadLetters" "${summary}" .deadLetters \
  '{"PENDING":0,"REPLAY_REQUESTED":0,"REPLAYED":0,"EXHAUSTED":0}'

acme="$(get '/api/v1/batches?clientId=ACME01&size=100' "${OPERATOR_TOKEN}")"
beta="$(get '/api/v1/batches?clientId=BETA02&size=100' "${OPERATOR_TOKEN}")"
check_json "batches of ACME01" "${acme}" .totalElements 3
check_json "batches of BETA02" "${beta}" .totalElements 2
all_batches="$(printf '%s\n%s\n' "${acme}" "${beta}" | jq -s '[.[].content[]?]')"

batch_id() {
  jq -r --arg f "$1" 'first(.[] | select(.fileName == $f) | .batchId) // empty' <<<"${all_batches}"
}

# file|status|failureReason|[total,accepted,rejected]|controlTotal|instructionsByStatus|rejectionCount
none='"ROUTED":0,"REQUIRES_REVIEW":0,"DUPLICATE":0,"DEAD_LETTERED":0,"REPLAYED":0'
expected_batches=(
  'PAY_ACME01_20261015093000.csv|COMPLETED|null|[10,10,0]|435730.74|{"ROUTED":10,"REQUIRES_REVIEW":0,"DUPLICATE":0,"DEAD_LETTERED":0,"REPLAYED":0}|0'
  'PAY_ACME01_20261015100000.csv|COMPLETED_WITH_ERRORS|null|[20,15,5]|201918.42|{"ROUTED":14,"REQUIRES_REVIEW":0,"DUPLICATE":0,"DEAD_LETTERED":0,"REPLAYED":0}|5'
  "PAY_ACME01_20261015110000.csv|FAILED|\"DUPLICATE_FILE\"|[0,0,0]|null|{${none}}|0"
  "PAY_BETA02_20261015120000.csv|FAILED|\"CONTROL_TOTAL_MISMATCH\"|[4,0,0]|54300.00|{${none}}|0"
  'PAY_BETA02_20261015130000.csv|COMPLETED|null|[6,6,0]|2514095.50|{"ROUTED":4,"REQUIRES_REVIEW":2,"DUPLICATE":0,"DEAD_LETTERED":0,"REPLAYED":0}|0'
)

for row in "${expected_batches[@]}"; do
  IFS='|' read -r file status reason counts total by_status rejections <<<"${row}"
  short="${file#PAY_}"
  short="${short%.csv}"
  id="$(batch_id "${file}")"
  if [[ -z "${id}" ]]; then
    record FAIL "${short} batch listed" "listed" "missing"
    continue
  fi
  detail="$(get "/api/v1/batches/${id}" "${OPERATOR_TOKEN}")"
  check_json "${short} status" "${detail}" .status "\"${status}\""
  check_json "${short} failureReason" "${detail}" .failureReason "${reason}"
  check_json "${short} rows total/accepted/rejected" "${detail}" '[.totalRows, .acceptedRows, .rejectedRows]' "${counts}"
  check_json "${short} controlTotal" "${detail}" .controlTotal "${total}"
  check_json "${short} instructionsByStatus" "${detail}" .instructionsByStatus "${by_status}"
  check_json "${short} rejections/dead letters" "${detail}" '[.rejectionCount, .deadLetterCount]' "[${rejections},0]"
done

acme_first="$(batch_id PAY_ACME01_20261015093000.csv)"
acme_errors="$(batch_id PAY_ACME01_20261015100000.csv)"

# Rejected rows of the 100000 file.
rejections="$(get "/api/v1/batches/${acme_errors}/rejections?size=100" "${OPERATOR_TOKEN}")"
check_json "100000 rejected rows (line, ref, reasons)" "${rejections}" \
  '[.content[] | [.lineNumber, .instructionRef, .reasonCodes]]' \
  '[[4,"ACME-0103",["INVALID_ACCOUNT"]],[7,"ACME-0106",["INVALID_AMOUNT"]],[10,"ACME-0109",["INVALID_CURRENCY"]],[14,"ACME-0113",["INVALID_VALUE_DATE"]],[17,"ACME-0116",["CHANNEL_LIMIT_EXCEEDED"]]]'
check_json "100000 rejected rawLine accounts masked" "${rejections}" \
  '[.content[].rawLine | contains("***")] | all' true

# Instructions of the 100000 file: the duplicate ACME-0003 belongs to the 093000 batch.
instructions="$(get "/api/v1/batches/${acme_errors}/instructions?size=100" "${OPERATOR_TOKEN}")"
check_json "100000 instructions" "${instructions}" .totalElements 14
check_json "100000 instructions exclude ACME-0003" "${instructions}" \
  '[.content[].instructionRef] | index("ACME-0003")' null
check_json "100000 instruction accounts masked" "${instructions}" \
  '[.content[] | .debtor.account, .creditor.account | startswith("***")] | all' true

# The duplicate instruction ACME-0003.
dup_key="$(sha256_hex 'ACME01|ACME-0003')"
dup="$(get "/api/v1/instructions/${dup_key}" "${OPERATOR_TOKEN}")"
check_json "ACME-0003 status" "${dup}" .status '"ROUTED"'
check_json "ACME-0003 batch is the 093000 file" "${dup}" .batchId "\"${acme_first}\""
check_json "ACME-0003 history" "${dup}" '[.history[] | [.status, .batchId, .detail]]' \
  "[[\"ROUTED\",\"${acme_first}\",null],[\"DUPLICATE\",\"${acme_errors}\",\"already ROUTED\"]]"
check_json "ACME-0003 masked for operator" "${dup}" \
  '[.accountsMasked, (.debtor.account | startswith("***"))]' '[true,true]'
dup_admin="$(get "/api/v1/instructions/${dup_key}" "${ADMIN_TOKEN}")"
check_json "ACME-0003 unmasked for admin" "${dup_admin}" \
  '[.accountsMasked, (.debtor.account | test("^[0-9]{7,12}$"))]' '[false,true]'

# Audit trail (admin only).
for row in "${expected_batches[@]}"; do
  IFS='|' read -r file status _ <<<"${row}"
  short="${file#PAY_}"
  short="${short%.csv}"
  id="$(batch_id "${file}")"
  [[ -n "${id}" ]] || continue
  audit="$(get "/api/v1/audit-events?entityId=${id}&size=100" "${ADMIN_TOKEN}")"
  if [[ "${status}" == FAILED ]]; then
    expected='{"BATCH_COMPLETED":1}'
  else
    expected='{"BATCH_COMPLETED":1,"BATCH_RECEIVED":1}'
  fi
  check_json "${short} audit batch events" "${audit}" \
    '[.content[] | select(.eventType | startswith("BATCH_")) | .eventType] | group_by(.) | map({(.[0]): length}) | add' \
    "${expected}"
done
audit="$(get "/api/v1/audit-events?entityId=${acme_errors}&eventType=INSTRUCTION_REJECTED&size=100" "${ADMIN_TOKEN}")"
check_json "100000 audit INSTRUCTION_REJECTED" "${audit}" .totalElements 5
audit="$(get '/api/v1/audit-events?entityId=operator&eventType=LOGIN_SUCCESS&size=1' "${ADMIN_TOKEN}")"
check_json "operator login audited" "${audit}" '.totalElements >= 1 and .content[0].actor == "user:operator"' true

# Role checks.
http GET /api/v1/batches
check "GET /batches without a token" 401 "${HTTP_STATUS}"
http GET /api/v1/audit-events "${OPERATOR_TOKEN}"
check "GET /audit-events as operator" 403 "${HTTP_STATUS}"
http POST /api/v1/dead-letters/1/replay "${OPERATOR_TOKEN}"
check "POST /dead-letters/1/replay as operator" 403 "${HTTP_STATUS}"

# Where the files ended on the SFTP server.
if sftp_available; then
  home="/home/${REMIT_SFTP_USER:-remit}"
  check "sftp /processed" \
    "PAY_ACME01_20261015093000.csv PAY_ACME01_20261015100000.csv PAY_BETA02_20261015130000.csv " \
    "$(sftp_listing "${home}/processed")"
  check "sftp /error" \
    "PAY_ACME01_20261015110000.csv PAY_ACME01_20261015110000.csv.error.txt PAY_BETA02_20261015120000.csv PAY_BETA02_20261015120000.csv.error.txt " \
    "$(sftp_listing "${home}/error")"
  check "sftp /inbound and /processing empty" "|" "$(sftp_listing "${home}/inbound")|$(sftp_listing "${home}/processing")"
  # shellcheck disable=SC2016 # expanded by the container's sh, not here
  reasons="$(sftp_sh 'head -n 1 "$1"; head -n 1 "$2"' \
    "${home}/error/PAY_ACME01_20261015110000.csv.error.txt" \
    "${home}/error/PAY_BETA02_20261015120000.csv.error.txt" | tr -d '\r' | tr '\n' ' ')"
  check "sftp .error.txt reason codes" "DUPLICATE_FILE CONTROL_TOTAL_MISMATCH " "${reasons}"
else
  log_warn "SFTP server of the ${target} target not reachable; skipping the SFTP directory checks"
fi

# ---------------------------------------------------------------------------------------------
# 5. Dead-letter cycle with the chaos hook (FORCE_FAIL: on in the docker profile and in the
#    local Kubernetes overlay's remit-config)
# ---------------------------------------------------------------------------------------------

# Compose and the local overlay pin the business date to 2026-10-15 unless .env says otherwise
# (ADR 0005).
export REMIT_CLOCK_FIXED_DATE="${REMIT_CLOCK_FIXED_DATE-2026-10-15}"
chaos_file="$("${REMIT_ROOT}/scripts/gen-batch.sh" -o "${work}" -r FORCE_FAIL CHAOS01 1)"
chaos_ref="$(sed -n '2p' "${chaos_file}" | cut -d, -f1)"
chaos_key="$(sha256_hex "CHAOS01|${chaos_ref}")"
seed "${chaos_file}"

log_info "waiting for ${chaos_ref} to be dead-lettered"
dead_letter="null"
list="null"
start="$(date +%s)"
while (($(date +%s) - start < settle_timeout)); do
  list="$(get '/api/v1/dead-letters?size=100' "${OPERATOR_TOKEN}")"
  dead_letter="$(jq -c --arg k "${chaos_key}" 'first(.content[]? | select(.idempotencyKey == $k)) // null' <<<"${list}")"
  [[ "${dead_letter}" != null ]] && break
  sleep 3
done
if [[ "${dead_letter}" == null ]]; then
  record FAIL "FORCE_FAIL dead letter appears" "PENDING dead letter" "none after ${settle_timeout}s"
else
  log_ok "dead letter appeared $(($(date +%s) - start))s after the upload"
  check_json "FORCE_FAIL dead letter" "${dead_letter}" \
    '{status, replayCount, replayable, exceptionClass}' \
    '{"status":"PENDING","replayCount":0,"replayable":true,"exceptionClass":"ca.musakaleem.remit.routing.error.RoutingException"}'
  dead_letter_id="$(jq -r .id <<<"${dead_letter}")"

  instruction="$(get "/api/v1/instructions/${chaos_key}" "${OPERATOR_TOKEN}")"
  check_json "FORCE_FAIL instruction status" "${instruction}" .status '"DEAD_LETTERED"'

  http POST "/api/v1/dead-letters/${dead_letter_id}/replay" "${ADMIN_TOKEN}"
  check "replay as admin" 202 "${HTTP_STATUS}"
  check_json "replay response" "${HTTP_BODY:-null}" '{status, replayCount}' \
    '{"status":"REPLAY_REQUESTED","replayCount":1}'
  http POST "/api/v1/dead-letters/${dead_letter_id}/replay" "${ADMIN_TOKEN}"
  check "second replay while pending" 409 "${HTTP_STATUS}"

  log_info "waiting for the replay to fail again and the dead letter to return to PENDING"
  current="null"
  start="$(date +%s)"
  while (($(date +%s) - start < 60)); do
    list="$(get '/api/v1/dead-letters?size=100' "${OPERATOR_TOKEN}")"
    current="$(jq -c --argjson id "${dead_letter_id}" 'first(.content[]? | select(.id == $id)) // null' <<<"${list}")"
    jq -e '.status == "PENDING"' <<<"${current}" >/dev/null 2>&1 && break
    sleep 2
  done
  check_json "dead letter after the failed replay" "${current}" '{status, replayCount}' \
    '{"status":"PENDING","replayCount":1}'
  check_json "dead letters of the FORCE_FAIL key" "${list}" \
    "[.content[] | select(.idempotencyKey == \"${chaos_key}\")] | length" 1
  audit="$(get "/api/v1/audit-events?entityId=${chaos_key}&eventType=DLT_REPLAY_REQUESTED" "${ADMIN_TOKEN}")"
  check_json "replay audited with the admin's name" "${audit}" '[.totalElements, .content[0].actor]' '[1,"user:admin"]'
fi

# ---------------------------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------------------------

print_results
if ((FAILURES > 0)); then
  log_error "smoke test FAILED"
  exit 1
fi
log_ok "smoke test PASSED"
