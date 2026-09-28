#!/usr/bin/env bash
# Restarts one Remit service in Docker Compose or Kubernetes and waits until it is healthy again.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/restart.sh [-h] [--timeout SECONDS] <service> [compose|k8s]

Restarts <service> and waits for it to be healthy.

  compose (default)  docker compose restart <service>, then wait for its healthcheck
  k8s                kubectl rollout restart deployment/<service> -n remit, then
                     kubectl rollout status (waits for the new pods to be ready); uses the
                     kind-remit context unless REMIT_KUBE_CONTEXT says otherwise

<service> is intake-service, routing-service or api-service; in compose any service of the
stack (mysql, kafka, ...) can be restarted as well.

Options:
  --timeout SECONDS  how long to wait (default 180)
  -h, --help         show this help

Examples:
  scripts/restart.sh routing-service
  scripts/restart.sh api-service k8s
EOF_USAGE
}

timeout=180
positional=()
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
    -*) die "unknown option $1 (see --help)" ;;
    *) positional+=("$1") ;;
  esac
  shift
done
[[ ${#positional[@]} -ge 1 && ${#positional[@]} -le 2 ]] || {
  usage >&2
  exit 1
}
service="${positional[0]}"
target="${positional[1]:-compose}"

restart_compose() {
  require_cmd docker
  compose config --services | grep -qx "${service}" ||
    die "unknown compose service '${service}' (see: docker compose config --services)"
  local start
  start="$(date +%s)"
  log_info "docker compose restart ${service}"
  compose restart "${service}"
  # The healthcheck needs a moment to notice the restart; until then the old status is reported.
  sleep 3
  wait_for_healthy "${timeout}" "${service}" || die "${service} did not become healthy"
  log_ok "${service} healthy again after $(($(date +%s) - start))s"
}

restart_k8s() {
  require_cmd kubectl
  local found=false svc
  for svc in "${REMIT_APP_SERVICES[@]}"; do
    [[ "${svc}" == "${service}" ]] && found=true
  done
  [[ "${found}" == true ]] || die "k8s restarts are for ${REMIT_APP_SERVICES[*]}, not '${service}'"
  local start
  start="$(date +%s)"
  log_info "kubectl rollout restart deployment/${service} -n ${REMIT_K8S_NAMESPACE}"
  kube rollout restart "deployment/${service}"
  kube rollout status "deployment/${service}" --timeout "${timeout}s" ||
    die "${service} did not become ready; see: kubectl -n ${REMIT_K8S_NAMESPACE} describe deployment/${service}"
  kube get pods --selector "app.kubernetes.io/name=${service}" >&2
  log_ok "${service} rolled out and ready after $(($(date +%s) - start))s"
}

case "${target}" in
  compose) restart_compose ;;
  k8s) restart_k8s ;;
  *) die "target must be compose or k8s, got '${target}'" ;;
esac
