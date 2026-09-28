#!/usr/bin/env bash
# Deploys the Remit stack to Docker Compose or to the local Kubernetes overlay and waits until
# every service reports healthy.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/deploy.sh [-h] [--build] [--timeout SECONDS] <compose|k8s>

Targets:
  compose   docker compose up -d, then wait until MySQL, MongoDB, Kafka, SFTP and the three
            services are healthy and kafka-init has finished. Prints each service's status.
  k8s       scripts/k8s-up.sh: creates the local kind cluster if needed, loads the images,
            applies the kustomize overlay k8s/overlays/local and waits for every rollout.

Options:
  --build            rebuild the service images first
  --timeout SECONDS  how long to wait for healthy services (compose default 180; k8s default
                     600 for each rollout)
  -h, --help         show this help

Requires .env (copy .env.example and replace every change-me value).

Exit codes: 0 deployed and healthy, 1 failed or timed out.
EOF_USAGE
}

build=false
timeout=""
target=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --build) build=true ;;
    --timeout)
      [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || die "--timeout needs a number of seconds"
      timeout="$2"
      shift
      ;;
    compose | k8s)
      [[ -z "${target}" ]] || die "only one target, got '${target}' and '$1'"
      target="$1"
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done
[[ -n "${target}" ]] || {
  usage >&2
  exit 1
}

deploy_compose() {
  require_cmd docker
  [[ -f "${REMIT_ROOT}/.env" ]] || die ".env is missing: cp .env.example .env and fill in the secrets"

  local args=(up -d) timeout="${timeout:-180}"
  if [[ "${build}" == true ]]; then
    args+=(--build)
  fi
  local start
  start="$(date +%s)"
  log_info "docker compose ${args[*]}"
  compose "${args[@]}"

  log_info "waiting up to ${timeout}s for healthy services"
  if ! wait_for_healthy "${timeout}" mysql mongo kafka kafka-init sftp "${REMIT_APP_SERVICES[@]}"; then
    die "deployment is not healthy; inspect with: docker compose ps, scripts/logs.sh <service>"
  fi
  log_ok "compose stack healthy in $(($(date +%s) - start))s"
  log_info "api-service:  http://localhost:${REMIT_API_PORT:-8080} (Swagger UI: /swagger-ui.html)"
}

deploy_k8s() {
  local args=()
  if [[ "${build}" == true ]]; then
    args+=(--build)
  fi
  if [[ -n "${timeout}" ]]; then
    args+=(--timeout "${timeout}")
  fi
  "${REMIT_ROOT}/scripts/k8s-up.sh" ${args[@]+"${args[@]}"}
}

load_env
case "${target}" in
  compose) deploy_compose ;;
  k8s) deploy_k8s ;;
esac
