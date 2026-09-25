#!/usr/bin/env bash
# One-time (and idempotent) setup of the local CI stack: SonarQube with the remit quality gate,
# the Jenkins webhook and an analysis token, then Jenkins configured from ci/jenkins/casc.yaml.
set -euo pipefail

# shellcheck source=../scripts/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/../scripts/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: ci/setup.sh [-h] [--timeout SECONDS]

Brings up ci/docker-compose.jenkins.yml and configures it. Safe to rerun.

  1. ci/.env from ci/.env.example on the first run, every change-me value replaced by a random one
  2. SonarQube and its database up; wait for STATUS=UP
  3. SonarQube admin password: admin/admin changed to SONAR_ADMIN_PASSWORD
  4. SonarQube in Standard Experience mode (blocker/critical severities, the terms of the spec)
  5. project ca.musakaleem.remit:remit and quality gate "remit" (Sonar way plus the spec
     conditions, on new code and on overall code), set as default and assigned to the project
  6. webhook "jenkins" -> http://jenkins:8080/sonarqube-webhook/
  7. user token "jenkins" written to SONAR_TOKEN in ci/.env (kept while it stays valid)
  8. dind and Jenkins built and started; Jenkins reads the token as credential sonar-token

Options:
  --timeout SECONDS  how long to wait for SonarQube and Jenkins each (default 300)
  -h, --help         show this help

Then open http://localhost:8090 (admin / JENKINS_ADMIN_PASSWORD from ci/.env) and run the job
"remit". Stop: docker compose -f ci/docker-compose.jenkins.yml down (add -v to drop all data).
EOF_USAGE
}

timeout=300
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --timeout)
      [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || die "--timeout needs a number of seconds"
      timeout="$2"
      shift
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

require_cmd docker curl jq od
CI_DIR="${REMIT_ROOT}/ci"
CI_ENV="${CI_DIR}/.env"
PROJECT_KEY="ca.musakaleem.remit:remit"
GATE="remit"
WEBHOOK_URL="http://jenkins:8080/sonarqube-webhook/"

# ci_compose <args>: docker compose for the CI stack, from the repository root.
ci_compose() {
  (cd "${REMIT_ROOT}" && MSYS_NO_PATHCONV=1 docker compose -f ci/docker-compose.jenkins.yml --env-file ci/.env "$@")
}

# random_hex <length>: lower-case hex characters from /dev/urandom (safe in URIs and .env files).
random_hex() {
  local out=""
  while ((${#out} < $1)); do
    out+="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  done
  printf '%s\n' "${out:0:$1}"
}

# set_env_value <key> <value>: replaces the KEY=... line in ci/.env.
set_env_value() {
  local tmp
  tmp="$(mktemp)"
  awk -v k="$1" -v v="$2" 'BEGIN { FS = "=" } $1 == k { print k "=" v; next } { print }' \
    "${CI_ENV}" >"${tmp}"
  cat "${tmp}" >"${CI_ENV}"
  rm -f "${tmp}"
}

# --- 1. ci/.env ---------------------------------------------------------------------------------

if [[ ! -f "${CI_ENV}" ]]; then
  log_info "creating ci/.env with random secrets"
  cp "${CI_DIR}/.env.example" "${CI_ENV}"
  chmod 600 "${CI_ENV}" 2>/dev/null || true
  while IFS='=' read -r key value; do
    [[ "${value}" == change-me* ]] || continue
    case "${key}" in
      SONAR_ADMIN_PASSWORD) set_env_value "${key}" "Sonar-$(random_hex 20)-A1" ;;
      REMIT_JWT_SECRET) set_env_value "${key}" "$(random_hex 64)" ;;
      *) set_env_value "${key}" "$(random_hex 24)" ;;
    esac
  done < <(grep -E '^[A-Z_]+=' "${CI_ENV}")
fi
load_env "${CI_ENV}"
for key in JENKINS_ADMIN_PASSWORD SONAR_ADMIN_PASSWORD SONAR_DB_PASSWORD; do
  [[ -n "${!key:-}" && "${!key}" != change-me* ]] || die "${key} is not set in ci/.env"
done
SONAR_URL="http://localhost:${SONARQUBE_PORT:-9000}"
JENKINS_URL="http://localhost:${JENKINS_PORT:-8090}"

# --- 2. SonarQube up ----------------------------------------------------------------------------

log_info "starting SonarQube (the first start takes a minute or two)"
ci_compose up -d sonar-db sonarqube
start="$(date +%s)"
until [[ "$(curl -fsS "${SONAR_URL}/api/system/status" 2>/dev/null | jq -r .status 2>/dev/null)" == UP ]]; do
  (($(date +%s) - start < timeout)) ||
    die "SonarQube is not UP after ${timeout}s (docker compose -f ci/docker-compose.jenkins.yml logs sonarqube)"
  sleep 5
done
log_ok "SonarQube is UP"

# sonar <method> <api path> [curl args...]: calls the Web API as admin; fails on HTTP errors.
sonar() {
  local method="$1" path="$2"
  shift 2
  curl -fsS -u "admin:${SONAR_ADMIN_PASSWORD}" -X "${method}" "$@" "${SONAR_URL}${path}"
}

# valid_login <user:password>: true when SonarQube accepts the credentials (or token followed by :).
valid_login() {
  [[ "$(curl -sS -u "$1" "${SONAR_URL}/api/authentication/validate" | jq -r .valid 2>/dev/null)" == true ]]
}

# --- 3. admin password --------------------------------------------------------------------------

if valid_login "admin:${SONAR_ADMIN_PASSWORD}"; then
  log_info "SonarQube admin password already set"
elif valid_login "admin:admin"; then
  log_info "changing the default SonarQube admin password"
  # The answer is not checked: some versions answer 400 although the password was changed (the
  # session of the old password ends). The login check below is what counts.
  curl -sS -o /dev/null -u admin:admin -X POST "${SONAR_URL}/api/users/change_password" \
    --data-urlencode login=admin --data-urlencode previousPassword=admin \
    --data-urlencode "password=${SONAR_ADMIN_PASSWORD}" || true
  valid_login "admin:${SONAR_ADMIN_PASSWORD}" ||
    die "changing the SonarQube admin password failed (SonarQube requires 12+ characters with upper and lower case, a digit and a special character)"
else
  die "cannot log in to SonarQube as admin with SONAR_ADMIN_PASSWORD or admin/admin"
fi

# --- 4. Standard Experience ---------------------------------------------------------------------

sonar PATCH /api/v2/clean-code-policy/mode -H 'Content-Type: application/merge-patch+json' \
  --data '{"mode":"STANDARD_EXPERIENCE"}' >/dev/null
log_info "SonarQube uses the Standard Experience (blocker and critical severities)"

# --- 5. project and quality gate ----------------------------------------------------------------

if [[ "$(sonar GET "/api/projects/search?projects=${PROJECT_KEY}" | jq '.components | length')" == 0 ]]; then
  log_info "creating project ${PROJECT_KEY}"
  sonar POST /api/projects/create --data-urlencode "project=${PROJECT_KEY}" \
    --data-urlencode name=Remit --data-urlencode mainBranch=main >/dev/null
fi

if ! sonar GET "/api/qualitygates/show?name=${GATE}" >/dev/null 2>&1; then
  log_info "creating quality gate ${GATE} from Sonar way"
  sonar POST /api/qualitygates/copy --data-urlencode "sourceName=Sonar way" \
    --data-urlencode "name=${GATE}" >/dev/null
fi

# metric|operator|error threshold. GT fails above the threshold, LT below it. The new-code
# conditions are the spec's; the overall ones make the gate meaningful on the first analysis,
# which has no new code yet.
conditions=(
  "new_coverage|LT|80"
  "new_blocker_violations|GT|0"
  "new_critical_violations|GT|0"
  "new_vulnerabilities|GT|0"
  "new_duplicated_lines_density|GT|2.99"
  "coverage|LT|80"
  "blocker_violations|GT|0"
  "critical_violations|GT|0"
  "vulnerabilities|GT|0"
  "duplicated_lines_density|GT|2.99"
)
gate_json="$(sonar GET "/api/qualitygates/show?name=${GATE}")"
for condition in "${conditions[@]}"; do
  IFS='|' read -r metric op error <<<"${condition}"
  id="$(jq -r --arg m "${metric}" 'first(.conditions[] | select(.metric == $m) | .id) // empty' <<<"${gate_json}")"
  if [[ -z "${id}" ]]; then
    sonar POST /api/qualitygates/create_condition --data-urlencode "gateName=${GATE}" \
      --data-urlencode "metric=${metric}" --data-urlencode "op=${op}" --data-urlencode "error=${error}" >/dev/null
  else
    sonar POST /api/qualitygates/update_condition --data-urlencode "id=${id}" \
      --data-urlencode "metric=${metric}" --data-urlencode "op=${op}" --data-urlencode "error=${error}" >/dev/null
  fi
done
sonar POST /api/qualitygates/set_as_default --data-urlencode "name=${GATE}" >/dev/null
sonar POST /api/qualitygates/select --data-urlencode "gateName=${GATE}" \
  --data-urlencode "projectKey=${PROJECT_KEY}" >/dev/null
log_ok "quality gate ${GATE} (default, assigned to ${PROJECT_KEY}):"
sonar GET "/api/qualitygates/show?name=${GATE}" | jq -r '.conditions[] | "  \(.metric) \(.op) \(.error)"' >&2

# --- 6. webhook ---------------------------------------------------------------------------------

webhook_key="$(sonar GET /api/webhooks/list | jq -r 'first(.webhooks[] | select(.name == "jenkins") | .key) // empty')"
if [[ -z "${webhook_key}" ]]; then
  sonar POST /api/webhooks/create --data-urlencode name=jenkins --data-urlencode "url=${WEBHOOK_URL}" >/dev/null
else
  sonar POST /api/webhooks/update --data-urlencode "webhook=${webhook_key}" \
    --data-urlencode name=jenkins --data-urlencode "url=${WEBHOOK_URL}" >/dev/null
fi
log_ok "webhook jenkins -> ${WEBHOOK_URL}"

# --- 7. analysis token --------------------------------------------------------------------------

token_changed=false
if [[ -n "${SONAR_TOKEN:-}" ]] && valid_login "${SONAR_TOKEN}:"; then
  log_info "SONAR_TOKEN in ci/.env is still valid"
else
  log_info "generating the SonarQube user token jenkins"
  sonar POST /api/user_tokens/revoke --data-urlencode login=admin --data-urlencode name=jenkins >/dev/null || true
  SONAR_TOKEN="$(sonar POST /api/user_tokens/generate --data-urlencode login=admin \
    --data-urlencode name=jenkins | jq -r .token)"
  [[ -n "${SONAR_TOKEN}" && "${SONAR_TOKEN}" != null ]] || die "SonarQube did not return a token"
  set_env_value SONAR_TOKEN "${SONAR_TOKEN}"
  export SONAR_TOKEN
  token_changed=true
fi

# --- 8. Jenkins ---------------------------------------------------------------------------------

log_info "building and starting dind and Jenkins"
# Without the default provenance attestation (it carries a timestamp) an unchanged image keeps its
# id, so a rerun does not recreate, and restart, a Jenkins that may be running a build.
BUILDX_NO_DEFAULT_ATTESTATIONS=1 ci_compose build jenkins
ci_compose up -d dind jenkins
if [[ "${token_changed}" == true ]]; then
  # Compose writes secrets when it creates the container; recreate it to hand over the new token.
  ci_compose up -d --force-recreate --no-deps jenkins
fi
start="$(date +%s)"
until curl -fsS -o /dev/null -u "admin:${JENKINS_ADMIN_PASSWORD}" "${JENKINS_URL}/job/remit/api/json" 2>/dev/null; do
  (($(date +%s) - start < timeout)) ||
    die "Jenkins or its remit job is not up after ${timeout}s (docker compose -f ci/docker-compose.jenkins.yml logs jenkins)"
  sleep 5
done
log_ok "Jenkins is up with the remit job"

cat >&2 <<EOF_DONE

  Jenkins    ${JENKINS_URL}  (admin / JENKINS_ADMIN_PASSWORD in ci/.env)
  SonarQube  ${SONAR_URL}  (admin / SONAR_ADMIN_PASSWORD in ci/.env)

  Run the pipeline: open ${JENKINS_URL}/job/remit/ and click "Build Now".
EOF_DONE
