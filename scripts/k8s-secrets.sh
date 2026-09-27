#!/usr/bin/env bash
# Creates or updates the remit-secrets Secret in namespace remit from .env (the keys documented in
# k8s/base/secret.example.yaml). Values are never printed and never passed on a command line: the
# manifest is built in memory and piped to kubectl apply.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/k8s-secrets.sh [-h] [--env-file FILE] [--restart]

Reads the secrets from .env (same parser as the other scripts; variables already set in the
environment win) and applies the Secret remit-secrets to namespace remit of the kind cluster.
Safe to rerun: the Secret is replaced with the current values.

Options:
  --env-file FILE  read FILE instead of <repo>/.env
  --restart        afterwards, rollout restart the three service deployments so they read the new
                   values (pods only read Secrets at startup) and wait for them
  -h, --help       show this help

Keys: MYSQL_ROOT_PASSWORD, REMIT_INTAKE_DB_PASSWORD, REMIT_ROUTING_DB_PASSWORD,
REMIT_API_DB_PASSWORD, MONGO_ROOT_USERNAME (default root), MONGO_ROOT_PASSWORD,
REMIT_MONGO_USER (default remit_audit), REMIT_MONGO_PASSWORD, REMIT_SFTP_USER (default remit),
REMIT_SFTP_PASSWORD, REMIT_JWT_SECRET (at least 32 bytes), REMIT_OPERATOR_PASSWORD,
REMIT_ADMIN_PASSWORD.

Environment:
  REMIT_KUBE_CONTEXT  kubectl context (default kind-remit; empty for the current context)

Database and SFTP passwords are only read by the infrastructure pods on their first start (empty
volume); changing them later needs the change made inside MySQL, MongoDB or SFTP as well, or a
fresh namespace (scripts/k8s-down.sh --keep-cluster, then scripts/k8s-up.sh).
EOF_USAGE
}

env_file="${REMIT_ROOT}/.env"
restart=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --env-file)
      [[ $# -ge 2 ]] || die "--env-file needs a file"
      env_file="$2"
      shift
      ;;
    --restart) restart=true ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

require_cmd kubectl
[[ -f "${env_file}" ]] || die "${env_file} is missing: cp .env.example .env and fill in the secrets"
load_env "${env_file}"

# Defaults for the user names, as in docker-compose.yml.
: "${MONGO_ROOT_USERNAME:=root}"
: "${REMIT_MONGO_USER:=remit_audit}"
: "${REMIT_SFTP_USER:=remit}"

keys=(
  MYSQL_ROOT_PASSWORD
  REMIT_INTAKE_DB_PASSWORD
  REMIT_ROUTING_DB_PASSWORD
  REMIT_API_DB_PASSWORD
  MONGO_ROOT_USERNAME
  MONGO_ROOT_PASSWORD
  REMIT_MONGO_USER
  REMIT_MONGO_PASSWORD
  REMIT_SFTP_USER
  REMIT_SFTP_PASSWORD
  REMIT_JWT_SECRET
  REMIT_OPERATOR_PASSWORD
  REMIT_ADMIN_PASSWORD
)

missing=()
for key in "${keys[@]}"; do
  [[ -n "${!key:-}" ]] || missing+=("${key}")
done
((${#missing[@]} == 0)) || die "not set in ${env_file}: ${missing[*]}"
((${#REMIT_JWT_SECRET} >= 32)) || die "REMIT_JWT_SECRET must be at least 32 bytes (openssl rand -base64 48)"

# b64 <value>: base64 on one line (GNU base64 wraps at 76 characters; tr works on every platform).
b64() {
  printf '%s' "$1" | base64 | tr -d '\n'
}

manifest() {
  cat <<EOF_MANIFEST
apiVersion: v1
kind: Secret
metadata:
  name: remit-secrets
  namespace: ${REMIT_K8S_NAMESPACE}
  labels:
    app.kubernetes.io/part-of: remit
type: Opaque
data:
EOF_MANIFEST
  local key
  for key in "${keys[@]}"; do
    printf '  %s: %s\n' "${key}" "$(b64 "${!key}")"
  done
}

kube get namespace "${REMIT_K8S_NAMESPACE}" >/dev/null 2>&1 ||
  die "namespace ${REMIT_K8S_NAMESPACE} does not exist (kubectl apply -f k8s/base/namespace.yaml, or scripts/k8s-up.sh)"
manifest | kube apply -f - >/dev/null
log_ok "secret remit-secrets applied with ${#keys[@]} keys (values not shown)"

if [[ "${restart}" == true ]]; then
  for svc in "${REMIT_APP_SERVICES[@]}"; do
    kube rollout restart "deployment/${svc}"
  done
  for svc in "${REMIT_APP_SERVICES[@]}"; do
    kube rollout status "deployment/${svc}" --timeout 300s
  done
  log_ok "services restarted with the new secret"
fi
