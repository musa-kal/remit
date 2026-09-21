#!/usr/bin/env bash
# Uploads payment batch files to the SFTP drop zone (/inbound), where intake-service picks them up
# on its next poll. Every file is first written as a hidden .<name>.part (the poller only lists
# *.csv) and all of them are renamed at the end, so the poller never reads a half-written file
# and sees the whole set in file name order.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/seed-sftp.sh [-h] [--via compose|sftp] [file...]

Uploads the given CSV files (default: every samples/*.csv) to /inbound on the SFTP server.

Options:
  --via compose  (default) stream each file into the running compose sftp container with
                 docker compose exec, owned by the SFTP user. Needs only the docker CLI, so it
                 works on Git Bash for Windows and in a Jenkins agent.
  --via sftp     upload with the sftp client over SSH, the way a real client would. Needs
                 sftp and sshpass (the password comes from REMIT_SFTP_PASSWORD).
  -h, --help     show this help

Environment (read from .env when not set):
  REMIT_SFTP_USER      SFTP user (default remit)
  REMIT_SFTP_PASSWORD  its password (sftp mode only)
  REMIT_SFTP_HOST      host for sftp mode (default localhost)
  REMIT_SFTP_PORT      port for sftp mode (default 2222)

Examples:
  scripts/seed-sftp.sh
  scripts/seed-sftp.sh samples/PAY_ACME01_20261015093000.csv
  scripts/seed-sftp.sh --via sftp /tmp/PAY_LOAD01_20261015120000.csv
EOF_USAGE
}

via=compose
files=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --via)
      [[ $# -ge 2 ]] || die "--via needs compose or sftp"
      via="$2"
      shift
      ;;
    --via=*) via="${1#--via=}" ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) files+=("$1") ;;
  esac
  shift
done
[[ "${via}" == compose || "${via}" == sftp ]] || die "--via must be compose or sftp, got '${via}'"

if [[ ${#files[@]} -eq 0 ]]; then
  shopt -s nullglob
  files=("${REMIT_ROOT}"/samples/*.csv)
  shopt -u nullglob
  [[ ${#files[@]} -gt 0 ]] || die "no files given and none found in samples/"
fi

names=()
for file in "${files[@]}"; do
  [[ -f "${file}" ]] || die "not a file: ${file}"
  name="$(basename "${file}")"
  [[ "${name}" == *.csv ]] || die "only .csv files are picked up by intake-service: ${name}"
  names+=("${name}")
done

load_env
sftp_user="${REMIT_SFTP_USER:-remit}"

seed_via_compose() {
  require_cmd docker
  [[ "$(service_state sftp)" == "running healthy" ]] ||
    die "the compose sftp service is not running and healthy (scripts/deploy.sh compose)"
  local inbound="/home/${sftp_user}/inbound" i
  for i in "${!files[@]}"; do
    # Runs as the SFTP user so intake-service, which logs in as that user, can move the file.
    # shellcheck disable=SC2016 # $1 is expanded by the container's sh, not here
    compose exec -T -u "${sftp_user}" sftp \
      sh -c 'cat >"$1"' sh "${inbound}/.${names[i]}.part" <"${files[i]}"
  done
  # One rename pass at the end, so the poller sees the whole set at once.
  # shellcheck disable=SC2016 # expanded by the container's sh, not here
  compose exec -T -u "${sftp_user}" sftp \
    sh -c 'cd "$1" && shift && for n in "$@"; do mv -f ".$n.part" "$n"; done' sh "${inbound}" "${names[@]}"
}

seed_via_sftp() {
  require_cmd sftp sshpass
  [[ -n "${REMIT_SFTP_PASSWORD:-}" ]] || die "REMIT_SFTP_PASSWORD is not set"
  local batch i
  batch="$(mktemp)"
  trap 'rm -f "${batch}"' RETURN
  for i in "${!files[@]}"; do
    printf 'put "%s" "/inbound/.%s.part"\n' "${files[i]}" "${names[i]}" >>"${batch}"
  done
  for i in "${!names[@]}"; do
    printf 'rename "/inbound/.%s.part" "/inbound/%s"\n' "${names[i]}" "${names[i]}" >>"${batch}"
  done
  # -b turns on BatchMode, which disables password prompts. ssh keeps the first value it gets, so
  # -o BatchMode=no must come before -b for sshpass to be able to answer the prompt.
  # The container's host key changes whenever it is recreated, so it is not pinned.
  SSHPASS="${REMIT_SFTP_PASSWORD}" sshpass -e sftp -q \
    -o BatchMode=no -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
    -b "${batch}" -P "${REMIT_SFTP_PORT:-2222}" "${sftp_user}@${REMIT_SFTP_HOST:-localhost}" >/dev/null
}

"seed_via_${via}"
for name in "${names[@]}"; do
  log_info "uploaded /inbound/${name}"
done
log_ok "${#names[@]} file(s) in /inbound via ${via}; intake-service picks them up on its next poll (every REMIT_SFTP_POLL_INTERVAL)"
