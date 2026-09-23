#!/usr/bin/env bash
# Runs the Postman collection with Newman against a running stack and writes JUnit and HTML
# reports to newman/. The passwords of the seeded users come from .env and are passed to Newman as
# --env-var, so they never end up in the committed environment files.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/newman.sh [-h] [-e local|docker] [--base-url URL] [--folder NAME]... [-- NEWMAN_ARGS...]

Runs postman/remit.postman_collection.json with the cli, junit and htmlextra reporters.
Reports: newman/junit.xml and newman/report.html. Exits with Newman's exit status.

The collection expects the state that scripts/smoke-test.sh --reset --keep leaves behind (the
five samples plus one dead-lettered FORCE_FAIL instruction); see docs/e2e-expectations.md. It can
be run repeatedly against the same stack.

Options:
  -e, --env local|docker  Postman environment (default local):
                            local   postman/local.postman_environment.json; the base URL is
                                    REMIT_API_URL or http://localhost:${REMIT_API_PORT}, plus /api/v1
                            docker  postman/docker.postman_environment.json (http://api-service:8080),
                                    for a Newman running in a container on the compose network
  --base-url URL          API base URL including /api/v1 (overrides the environment)
  --folder NAME           run only this folder; repeatable (later folders need ids that earlier
                          folders discover, so run them in collection order)
  -h, --help              show this help
  --                      pass the remaining arguments to newman unchanged

Environment (read from .env when not set):
  REMIT_OPERATOR_PASSWORD  password of the seeded operator user
  REMIT_ADMIN_PASSWORD     password of the seeded admin user
  REMIT_API_URL            API URL without /api/v1 for -e local (default http://localhost:8080)
  NEWMAN                   newman command (default newman; needs newman-reporter-htmlextra)

Examples:
  scripts/smoke-test.sh --reset --keep && scripts/newman.sh
  scripts/newman.sh --folder Auth --folder Batches
  scripts/newman.sh -e docker --base-url http://localhost:8080/api/v1
EOF_USAGE
}

env_name=local
base_url=""
folders=()
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    -e | --env)
      [[ $# -ge 2 ]] || die "$1 needs local or docker"
      env_name="$2"
      shift
      ;;
    --base-url)
      [[ $# -ge 2 && -n "$2" ]] || die "--base-url needs a URL"
      base_url="$2"
      shift
      ;;
    --folder)
      [[ $# -ge 2 && -n "$2" ]] || die "--folder needs a folder name"
      folders+=("$2")
      shift
      ;;
    --)
      shift
      extra=("$@")
      break
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

[[ "${env_name}" == local || "${env_name}" == docker ]] ||
  die "-e must be local or docker, got '${env_name}'"

newman_cmd="${NEWMAN:-newman}"
require_cmd "${newman_cmd}"
load_env
[[ -n "${REMIT_OPERATOR_PASSWORD:-}" ]] || die "REMIT_OPERATOR_PASSWORD is not set (.env)"
[[ -n "${REMIT_ADMIN_PASSWORD:-}" ]] || die "REMIT_ADMIN_PASSWORD is not set (.env)"

if [[ -z "${base_url}" && "${env_name}" == local ]]; then
  api="${REMIT_API_URL:-http://localhost:${REMIT_API_PORT:-8080}}"
  base_url="${api%/}/api/v1"
fi

collection="${REMIT_ROOT}/postman/remit.postman_collection.json"
environment="${REMIT_ROOT}/postman/${env_name}.postman_environment.json"
out="${REMIT_ROOT}/newman"
mkdir -p "${out}"

args=(
  run "${collection}"
  --environment "${environment}"
  --env-var "operatorPassword=${REMIT_OPERATOR_PASSWORD}"
  --env-var "adminPassword=${REMIT_ADMIN_PASSWORD}"
  --reporters "cli,junit,htmlextra"
  --reporter-junit-export "${out}/junit.xml"
  --reporter-htmlextra-export "${out}/report.html"
  --reporter-htmlextra-title "Remit API tests"
)
if [[ -n "${base_url}" ]]; then
  args+=(--env-var "baseUrl=${base_url}")
fi
for folder in ${folders[@]+"${folders[@]}"}; do
  args+=(--folder "${folder}")
done
args+=(${extra[@]+"${extra[@]}"})

log_info "newman: ${env_name} environment, base URL ${base_url:-from the environment file}"
status=0
"${newman_cmd}" "${args[@]}" || status=$?
if ((status == 0)); then
  log_ok "newman passed; reports in newman/junit.xml and newman/report.html"
else
  log_error "newman failed with exit status ${status}; reports in newman/junit.xml and newman/report.html"
fi
exit "${status}"
