#!/usr/bin/env bash
# Tails the JSON logs of a compose service or of the pods of a Kubernetes deployment and
# pretty-prints them with jq. Lines that are not JSON (for example a JVM warning on stderr) are
# printed as they are.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/logs.sh [-h] [-n LINES] [--no-follow] [-c] <service> [compose|k8s]

Follows the logs of <service> (intake-service, routing-service, api-service, or an
infrastructure service such as kafka) and pretty-prints every JSON line with jq.

  compose (default)  docker compose logs <service>
  k8s                kubectl logs of every pod labelled app.kubernetes.io/name=<service> in
                     namespace remit (both routing-service replicas, interleaved)

Options:
  -n LINES     number of existing lines to show first (default 50, "all" for everything)
  --no-follow  print and exit instead of following
  -c           one compact JSON object per line instead of indented output
  -h, --help   show this help

Environment:
  REMIT_KUBE_CONTEXT  kubectl context for k8s (default kind-remit; empty for the current one)

Examples:
  scripts/logs.sh intake-service
  scripts/logs.sh -n all --no-follow routing-service | less
  scripts/logs.sh -c api-service | grep correlationId
  scripts/logs.sh routing-service k8s
EOF_USAGE
}

tail_lines=50
follow=true
compact=false
positional=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    -n)
      [[ $# -ge 2 ]] || die "-n needs a number of lines"
      tail_lines="$2"
      shift
      ;;
    --no-follow) follow=false ;;
    -c) compact=true ;;
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
[[ "${target}" == compose || "${target}" == k8s ]] || die "target must be compose or k8s, got '${target}'"
[[ "${tail_lines}" == all || "${tail_lines}" =~ ^[0-9]+$ ]] || die "-n must be a number or all"

jq_args=(--unbuffered -R -r)
if [[ "${compact}" == true ]]; then
  jq_args+=(-c)
fi

if [[ "${target}" == k8s ]]; then
  require_cmd kubectl jq
  # kubectl uses -1 for all lines.
  args=(logs --selector "app.kubernetes.io/name=${service}" --tail "${tail_lines/all/-1}"
    --prefix=false --max-log-requests 10)
  if [[ "${follow}" == true ]]; then
    args+=(-f)
  fi
  [[ -n "$(kube get pods --selector "app.kubernetes.io/name=${service}" -o name 2>/dev/null)" ]] ||
    die "no pods labelled app.kubernetes.io/name=${service} in namespace ${REMIT_K8S_NAMESPACE}"
  kube "${args[@]}" 2>&1 | jq "${jq_args[@]}" 'fromjson? // .'
else
  require_cmd docker jq
  args=(logs --no-log-prefix --tail "${tail_lines}")
  if [[ "${follow}" == true ]]; then
    args+=(-f)
  fi
  compose "${args[@]}" "${service}" 2>&1 | jq "${jq_args[@]}" 'fromjson? // .'
fi
