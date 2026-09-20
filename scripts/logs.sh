#!/usr/bin/env bash
# Tails the JSON logs of a compose service and pretty-prints them with jq. Lines that are not
# JSON (for example a JVM warning on stderr) are printed as they are.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/logs.sh [-h] [-n LINES] [--no-follow] [-c] <service>

Follows docker compose logs of <service> (intake-service, routing-service, api-service, or any
other compose service) and pretty-prints every JSON line with jq.

Options:
  -n LINES     number of existing lines to show first (default 50, "all" for everything)
  --no-follow  print and exit instead of following
  -c           one compact JSON object per line instead of indented output
  -h, --help   show this help

Examples:
  scripts/logs.sh intake-service
  scripts/logs.sh -n all --no-follow routing-service | less
  scripts/logs.sh -c api-service | grep correlationId
EOF_USAGE
}

tail_lines=50
follow=true
compact=false
service=""
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
    *)
      [[ -z "${service}" ]] || die "only one service, got '${service}' and '$1'"
      service="$1"
      ;;
  esac
  shift
done
[[ -n "${service}" ]] || {
  usage >&2
  exit 1
}
[[ "${tail_lines}" == all || "${tail_lines}" =~ ^[0-9]+$ ]] || die "-n must be a number or all"

require_cmd docker jq

args=(logs --no-log-prefix --tail "${tail_lines}")
if [[ "${follow}" == true ]]; then
  args+=(-f)
fi
jq_args=(--unbuffered -R -r)
if [[ "${compact}" == true ]]; then
  jq_args+=(-c)
fi

compose "${args[@]}" "${service}" 2>&1 | jq "${jq_args[@]}" 'fromjson? // .'
